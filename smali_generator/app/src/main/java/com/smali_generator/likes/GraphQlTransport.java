package com.smali_generator.likes;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issues GraphQL queries through the app's own OkHttp client.
 *
 * This is the reason the feature needs no credentials: executing on the app's
 * client runs the app's interceptor chain, which attaches the Authorization
 * header, so the session token is never read, stored or reconstructed here.
 * The endpoint is likewise taken from an observed request rather than written
 * down.
 */
public final class GraphQlTransport {

    /** Immutable pair, published through one reference so readers never see half a capture. */
    private static final class Captured {
        final Object client;     // okhttp3.OkHttpClient
        final String endpoint;   // e.g. https://host/graphql

        Captured(Object client, String endpoint) {
            this.client = client;
            this.endpoint = endpoint;
        }
    }

    private static final AtomicReference<Captured> CAPTURED = new AtomicReference<>();
    private static final AtomicBoolean WARNED_NOT_READY = new AtomicBoolean();

    private GraphQlTransport() {
    }

    /** First capture wins, atomically. */
    public static void capture(Object okHttpClient, String url) {
        if (okHttpClient == null || url == null) {
            return;
        }
        if (CAPTURED.compareAndSet(null, new Captured(okHttpClient, url))) {
            Log.w("GraphQlTransport: captured the app's client");
        }
    }

    public static boolean isReady() {
        return CAPTURED.get() != null;
    }

    public static String post(String operationName, String document, String variablesJson) {
        Captured cap = CAPTURED.get();
        if (cap == null) {
            // The only thing distinguishing "no GraphQL traffic yet" from "the
            // getter was inlined and we never captured" -- say so once.
            if (WARNED_NOT_READY.compareAndSet(false, true)) {
                Log.w("GraphQlTransport: post called before any client was captured");
            }
            return null;
        }
        Object c = cap.client;
        String url = cap.endpoint;
        Object response = null;
        Class<?> responseC = null;
        try {
            String body = new org.json.JSONObject()
                    .put("operationName", operationName)
                    .put("query", document)
                    .put("variables", new org.json.JSONObject(variablesJson))
                    .toString();

            ClassLoader cl = c.getClass().getClassLoader();
            Class<?> mediaTypeC = Class.forName("okhttp3.MediaType", true, cl);
            Class<?> requestBodyC = Class.forName("okhttp3.RequestBody", true, cl);
            Class<?> requestC = Class.forName("okhttp3.Request", true, cl);
            Class<?> builderC = Class.forName("okhttp3.Request$Builder", true, cl);
            Class<?> callC = Class.forName("okhttp3.Call", true, cl);
            responseC = Class.forName("okhttp3.Response", true, cl);

            Method parse = mediaTypeC.getMethod("parse", String.class);
            Object json = parse.invoke(null, "application/json; charset=utf-8");

            Method create = requestBodyC.getMethod("create", mediaTypeC, String.class);
            Object requestBody = create.invoke(null, json, body);

            Object builder = builderC.getConstructor().newInstance();
            // The app appends the operation name as a path segment; match it.
            builderC.getMethod("url", String.class).invoke(builder, url + "/" + operationName);
            builderC.getMethod("post", requestBodyC).invoke(builder, requestBody);
            Object request = builderC.getMethod("build").invoke(builder);

            Object call = c.getClass().getMethod("newCall", requestC).invoke(c, request);
            response = callC.getMethod("execute").invoke(call);
            Object responseBody = responseC.getMethod("body").invoke(response);
            if (responseBody == null) {
                return null;
            }
            return (String) responseBody.getClass().getMethod("string").invoke(responseBody);
        } catch (Throwable t) {
            Log.w("GraphQlTransport: request failed -- " + t);
            return null;
        } finally {
            if (response != null && responseC != null) {
                try {
                    responseC.getMethod("close").invoke(response);
                } catch (Throwable ignored) {
                    // Nothing useful to do; never throw from post.
                }
            }
        }
    }
}
