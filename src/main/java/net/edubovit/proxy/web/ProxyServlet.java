package net.edubovit.proxy.web;

import org.springframework.beans.factory.annotation.Value;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@WebServlet
public class ProxyServlet extends HttpServlet {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
                    + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
                    + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${net.edubovit.proxy.host}")
    private String proxyHost;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String host = req.getServerName();
        String actualHost = host.replaceFirst("\\" + proxyHost, "");
        var uri = URI.create("https://" + actualHost + req.getRequestURI());
        var requestBuilder = HttpRequest.newBuilder(uri)
                .method(req.getMethod(), HttpRequest.BodyPublishers.noBody());
        req.getHeaderNames().asIterator().forEachRemaining(name -> {
            try {
                String value = req.getHeader(name);
                if (name.equals("accept-encoding")) {
                    value = value.replaceAll("gzip", "");
                }
                requestBuilder.header(name, value);
            } catch (IllegalArgumentException ignored) {
            }
        });
        var request = requestBuilder.build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        response.headers().map().forEach((name, valueList) -> valueList.forEach(value -> resp.addHeader(name, value)));
        resp.setStatus(response.statusCode());
        String body = response.body();
        String editedBody = body.replaceAll(actualHost, host);
        var scannedBody = new StringBuilder();
        var matcher = URL_PATTERN.matcher(editedBody);
        int prevEnd = 0;
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end();
            String substring = editedBody.substring(start, end);
            if (!substring.startsWith("http")) {
                substring = "http://" + substring;
            }
            var url = new URL(substring);
            boolean edited = false;
            String urlHost = url.getHost();
            if (!urlHost.endsWith(proxyHost)) {
                urlHost = urlHost + proxyHost;
                edited = true;
            }
            if (!url.getProtocol().equals("http")) {
                edited = true;
            }
            if (edited) {
                String editedUrl = new URL("http", urlHost, url.getFile()).toString();
                scannedBody.append(editedBody, prevEnd, start);
                scannedBody.append(editedUrl);
                prevEnd = end;
            }
        }
        scannedBody.append(editedBody);
        resp.getOutputStream().print(scannedBody.toString());
    }

}
