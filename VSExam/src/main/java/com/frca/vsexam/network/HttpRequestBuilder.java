package com.frca.vsexam.network;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.frca.vsexam.entities.exam.Exam;
import com.frca.vsexam.exceptions.NoAuthException;
import com.frca.vsexam.helper.DataHolder;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.message.BasicNameValuePair;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class HttpRequestBuilder {

    public final static String KEY_LOGIN = "key_login";

    public final static String KEY_PASSWORD = "key_password";

    public final static String AUTH_BASE_URL = "https://isis.vse.cz/auth/";

    public final static String UNAUTH_BASE_URL = "https://isis.vse.cz/";

    public final static String USER_AGENT = "Mozilla/5.0 (Windows NT 6.3; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/30.0.1599.101 Safari/537.36";

    private final DataHolder dataHolder;

    private Class<? extends HttpRequestBase> requestType = HttpGet.class;

    private String partialUrl;

    private boolean authorized;

    private AbstractHttpEntity entity;

    private HttpRequestBase request = null;

    public HttpRequestBuilder(Context context,  String partialUrl) {
        this(DataHolder.getInstance(context), partialUrl);
    }

    private HttpRequestBuilder(DataHolder dataHolder,  String partialUrl) {
        this.dataHolder = dataHolder;
        this.partialUrl = partialUrl;
    }

    public static HttpRequestBuilder getAuthorizedRequestBuilder(DataHolder dataHolder,  String partialUrl) {
        HttpRequestBuilder builder = new HttpRequestBuilder(dataHolder, partialUrl);
        builder.authorized = true;
        return builder;
    }

    public static HttpRequestBuilder getUnAuthorizedRequestBuilder(DataHolder dataHolder,  String partialUrl) {
        HttpRequestBuilder builder = new HttpRequestBuilder(dataHolder, partialUrl);
        builder.authorized = false;
        return builder;
    }

    public HttpRequestBase build() throws NoAuthException {
        if (request != null)
            return request;

        try {
            request = requestType.getDeclaredConstructor(String.class).newInstance(completeURLString(partialUrl, authorized));
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }

        request.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        request.setHeader("Host", "isis.vse.cz");
        if (authorized)
            request.setHeader("Authorization", getB64Auth());
        request.setHeader("Accept-Encoding", "gzip,deflate,sdch");
        request.setHeader("Accept-Language", dataHolder.getConfiguration().locale.getLanguage() + ",en;q=0.8");
        request.setHeader("Connection", "keep-alive");
        request.setHeader("Origin", "https://isis.vse.cz");
        request.setHeader("User-Agent", USER_AGENT);

        if (request instanceof HttpEntityEnclosingRequestBase)
            ((HttpPost)request).setEntity(entity);

        return request;
    }

    public void setHttpEntity(Class<? extends HttpRequestBase> requestType, AbstractHttpEntity entity) {
        this.requestType = requestType;
        this.entity = entity;
    }

    public HttpRequestBase getRequest() {
        return request;
    }

    private String getB64Auth () throws NoAuthException {
        String login = dataHolder.getPreferences().getString(KEY_LOGIN, null);
        String password = dataHolder.getPreferences().getString(KEY_PASSWORD, null);

        if (TextUtils.isEmpty(login) || TextUtils.isEmpty(password))
            throw new NoAuthException();

        String source = login + ":" + password;
        String auth = "Basic "+ Base64.encodeToString(source.getBytes(), Base64.URL_SAFE | Base64.NO_WRAP);
        return auth;
    }

    private String getHost() {
        String host = authorized ? AUTH_BASE_URL : UNAUTH_BASE_URL;
        if (host.startsWith("https://"))
            host = host.substring(7);

        host = host.substring(0, host.indexOf("/"));
        return host;
    }

    public static String completeURLString(String url, boolean authorized) {
        if (url.startsWith("https://"))
            return url;

        boolean slashPartial = (int)url.charAt(0) == 0x2f;
        String prependUrl = authorized ? AUTH_BASE_URL : UNAUTH_BASE_URL;
        if (isSlashEnding(prependUrl) && slashPartial)
            url = prependUrl + url.substring(1);
        else if (isSlashEnding(prependUrl) || slashPartial)
            url = prependUrl + url;
        else
            url = prependUrl + "/" + url;

        return url;
    }

    public static boolean isSlashEnding(String url) {
        return (int)url.charAt(url.length()-1) == 0x2f;
    }

    public boolean isBuilt() {
        return request != null;
    }

    public static HttpRequestBase getRegisterRequest(DataHolder holder, Exam exam, boolean apply) {
        // TODO: Disable in production
        /*try {
            return new HttpRequestBuilder(holder, "student/terminy_seznam.pl").build();
        } catch (NoAuthException e) {
            e.printStackTrace();
        }*/

        HttpRequestBuilder builder = HttpRequestBuilder.getAuthorizedRequestBuilder(holder, "student/terminy_prihlaseni.pl");

        try {
            List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
            urlParameters.add(new BasicNameValuePair("termin", String.valueOf(exam.getId())));
            urlParameters.add(new BasicNameValuePair("predmet", ""));
            urlParameters.add(new BasicNameValuePair("studium", String.valueOf(exam.getStudyId())));
            urlParameters.add(new BasicNameValuePair("obdobi", String.valueOf(exam.getPeriodId())));
            if (apply) {
                if (exam.getRegisteredOnId() != 0) {
                    urlParameters.add(new BasicNameValuePair("odhlas_termin", String.valueOf(exam.getRegisteredOnId())));
                    urlParameters.add(new BasicNameValuePair("odhlasit_prihlasit", "Přihlásit na termín"));
                } else {
                    urlParameters.add(new BasicNameValuePair("prihlasit", "Přihlásit na termín"));
                }
            } else {
                urlParameters.add(new BasicNameValuePair("odhlasit", "Odhlásit z termínu"));
            }

            builder.setHttpEntity(HttpPost.class, new UrlEncodedFormEntity(urlParameters, "iso-8859-2"));
            HttpPost post = (HttpPost) builder.build();
            post.setHeader("Referer", "https://isis.vse.cz/auth/student/terminy_prihlaseni.pl");
            return post;

        } catch (NoAuthException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void setGetArguments(Map<String, String> urlParameters, char nameValueSeparator, char argSeparator) {
        StringBuilder sb = new StringBuilder();
        Iterator itr = urlParameters.entrySet().iterator();
        while (itr.hasNext()) {
            Map.Entry entry = (Map.Entry) itr.next();
            sb.append(entry.getKey());
            sb.append(nameValueSeparator);
            sb.append(entry.getValue());

            if (itr.hasNext())
                sb.append(argSeparator);
        }

        int idx = partialUrl.indexOf("?");
        if (idx >= 0)
            partialUrl = partialUrl.substring(0, partialUrl.indexOf("?"));

        partialUrl += "?" + sb.toString();
    }

    public static Map<String, String> getGetArguments(String url, char nameValueSeparator, char argSeparator) {
        String args = url.substring(url.indexOf("?")+1);
        String[] argParts = args.split(String.valueOf(argSeparator));
        Map<String, String> result = new HashMap<String, String>();
        for (String part : argParts) {
            int idx = part.indexOf(nameValueSeparator);
            result.put(part.substring(0, idx), part.substring(idx+1));
        }

        return result;
    }

    public static HttpRequestBase getPlanRequest(DataHolder holder, Map<String, String> urlParameters) {
        HttpRequestBuilder builder = HttpRequestBuilder.getUnAuthorizedRequestBuilder(holder, "katalog/plany.pl");
        builder.setGetArguments(urlParameters, '=', ';');
        try {
            return builder.build();
        } catch (NoAuthException e) {
            return null;
        }
    }
}
