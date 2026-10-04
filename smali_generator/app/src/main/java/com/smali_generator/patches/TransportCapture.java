package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.likes.GraphQlTransport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures the app's OkHttp client the first time it issues a GraphQL request.
 *
 * Target: {@code okhttp3.internal.connection.RealCall.getClient()}. It is a
 * pure field getter whose first bytecode instruction is {@code iget-object}
 * (checked in the extracted smali), so it is reimplemented outright with the
 * two-argument ArtHooks form -- there is NO backup and nothing calls through.
 * The earlier design hooked {@code OkHttpClient.newCall} and called through to
 * a backup; that method begins with {@code const-string}, and the process died
 * with SIGSEGV on cold launch. The mechanism is a hypothesis, not an
 * established fact -- NOTES.md, "The ArtHooks backup-entry-instruction rule",
 * is the single source for the evidence and its limits.
 *
 * {@code thiz} is a RealCall, which holds both the executing client and the
 * original request, so client and endpoint are captured together and the
 * client is the one that actually carries the GraphQL traffic.
 *
 * okhttp3 is not obfuscated in this APK, so no finder is needed.
 */
public class TransportCapture implements Hook {

    private static volatile Field clientField;       // RealCall.client
    private static volatile Field requestField;      // RealCall.originalRequest
    private static volatile Method urlMethod;        // Request.url()
    private static volatile Method pathMethod;       // HttpUrl.encodedPath()
    private static volatile boolean done;
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /** Reimplementation of RealCall.getClient(); instance target, so leading thiz. */
    static Object get_client_hook(Object thiz) {
        Object client = null;
        try {
            client = clientField.get(thiz);
        } catch (Throwable t) {
            // load() proved this lookup works, so this should not happen. The
            // real getter never returns null here, so make it diagnosable.
            if (WARNED.compareAndSet(false, true)) {
                Log.e(HookUtil.TAG, "TransportCapture: client read failed -- " + t);
            }
        }
        if (!done && client != null) {
            try {
                observe(client, thiz);
            } catch (Throwable ignored) {
                // Never interfere with the app's own networking.
            }
        }
        return client;
    }

    /**
     * Cheap pre-capture check: two cached reflective calls on the parsed
     * HttpUrl, no string building unless the path actually matches.
     */
    private static void observe(Object client, Object realCall) throws Exception {
        Object request = requestField.get(realCall);
        if (request == null) {
            return;
        }
        Object httpUrl = urlMethod.invoke(request);
        if (httpUrl == null) {
            return;
        }
        String path = (String) pathMethod.invoke(httpUrl);
        // "/graphql" exactly or as a leading segment; not "/graphqlfoo".
        if (path == null || !(path.equals("/graphql") || path.startsWith("/graphql/"))) {
            return;
        }
        String url = String.valueOf(httpUrl);
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : url.indexOf('/', schemeEnd + 3);
        if (pathStart > 0 && url.startsWith("/graphql", pathStart)) {
            GraphQlTransport.capture(client, url.substring(0, pathStart + "/graphql".length()));
            done = GraphQlTransport.isReady();
        }
    }

    @Override
    public void load() {
        try {
            Class<?> realCall = Class.forName("okhttp3.internal.connection.RealCall",
                    false, HookUtil.class.getClassLoader());
            Field c = realCall.getDeclaredField("client");
            Field r = realCall.getDeclaredField("originalRequest");
            c.setAccessible(true);
            r.setAccessible(true);
            Class<?> requestC = Class.forName("okhttp3.Request", false,
                    HookUtil.class.getClassLoader());
            Class<?> httpUrlC = Class.forName("okhttp3.HttpUrl", false,
                    HookUtil.class.getClassLoader());
            Method um = requestC.getMethod("url");
            Method pm = httpUrlC.getMethod("encodedPath");
            clientField = c;
            requestField = r;
            urlMethod = um;
            pathMethod = pm;

            Method replacement = TransportCapture.class.getDeclaredMethod(
                    "get_client_hook", Object.class);
            HookUtil.install("TransportCapture",
                    "okhttp3.internal.connection.RealCall",
                    "getClient",
                    "()Lokhttp3/OkHttpClient;",
                    replacement);
        } catch (Throwable t) {
            // Includes a different okhttp layout: install nothing rather than
            // risk replacing a getter we cannot reimplement.
            Log.e(HookUtil.TAG, "TransportCapture: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
