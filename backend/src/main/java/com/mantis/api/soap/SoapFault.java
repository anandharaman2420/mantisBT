package com.mantis.api.soap;

/** A SOAP 1.1 fault raised by the compatibility layer. */
public class SoapFault extends RuntimeException {
    /** SOAP fault code: {@code Client} (bad request) or {@code Server} (application error). */
    private final String code;

    public SoapFault(String code, String message) {
        super(message);
        this.code = code == null ? "Server" : code;
    }

    public String getCode() { return code; }
}
