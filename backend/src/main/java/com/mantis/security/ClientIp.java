package com.mantis.security;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Address of the caller of the current HTTP request (works in controllers and in SOAP/REST endpoints). */
public final class ClientIp {
    private ClientIp() {}

    public static String current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)
            return a.getRequest().getRemoteAddr();
        return "unknown";
    }
}
