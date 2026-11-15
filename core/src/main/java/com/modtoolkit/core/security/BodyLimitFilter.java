package com.modtoolkit.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses request bodies over the limit with a 413, as the proxy's client_max_body_size did. Tomcat's own limits cover
 * only form posts, not JSON. A declared length is checked unread; a body without one (chunked, or HTTP/2) is read up
 * to the limit first, so no handler ever sees part of an oversized body.
 */
public class BodyLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODYLESS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final long limit;

    public BodyLimitFilter(long limit) {
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long declared = req.getContentLengthLong();
        if (declared > limit) {
            tooLarge(res);
            return;
        }
        if (declared < 0 && !BODYLESS.contains(req.getMethod())) {
            byte[] body = req.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
            if (body.length > limit) {
                tooLarge(res);
                return;
            }
            chain.doFilter(new Buffered(req, body), res);
            return;
        }
        chain.doFilter(req, res);
    }

    private void tooLarge(HttpServletResponse res) throws IOException {
        res.setStatus(413);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"the request body is over " + limit / 1024
                + " KB\",\"hint\":\"send a smaller request\"}}");
    }

    private static final class Buffered extends HttpServletRequestWrapper {
        private final byte[] body;

        Buffered(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                    throw new UnsupportedOperationException("the body was already read");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
