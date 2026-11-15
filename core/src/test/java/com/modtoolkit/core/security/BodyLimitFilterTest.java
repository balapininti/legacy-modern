package com.modtoolkit.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BodyLimitFilterTest {
    final BodyLimitFilter filter = new BodyLimitFilter(10);

    MockHttpServletResponse run(MockHttpServletRequest req, AtomicReference<byte[]> read) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> read.set(rq.getInputStream().readAllBytes()));
        return res;
    }

    static MockHttpServletRequest post(byte[] body, boolean withLength) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/x") {
            @Override
            public int getContentLength() {
                return withLength ? super.getContentLength() : -1;
            }

            @Override
            public long getContentLengthLong() {
                return withLength ? super.getContentLengthLong() : -1;
            }
        };
        r.setContent(body);
        return r;
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedUnread() throws Exception {
        AtomicReference<byte[]> read = new AtomicReference<>();
        MockHttpServletResponse res = run(post(new byte[11], true), read);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(res.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(read.get()).isNull();
    }

    @Test
    void anUndeclaredLengthOverTheLimitIsRefused() throws Exception {
        AtomicReference<byte[]> read = new AtomicReference<>();
        assertThat(run(post(new byte[11], false), read).getStatus()).isEqualTo(413);
        assertThat(read.get()).isNull();
    }

    @Test
    void bodiesWithinTheLimitPassWhole() throws Exception {
        for (boolean withLength : new boolean[] {true, false}) {
            AtomicReference<byte[]> read = new AtomicReference<>();
            MockHttpServletResponse res = run(post("0123456789".getBytes(), withLength), read);
            assertThat(res.getStatus()).isEqualTo(200);
            assertThat(new String(read.get())).isEqualTo("0123456789");
        }
    }
}
