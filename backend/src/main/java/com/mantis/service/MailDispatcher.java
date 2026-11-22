package com.mantis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Sends one e-mail in the background; a failing SMTP server must never break the user's request. */
@Component
public class MailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);
    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    public MailDispatcher(ObjectProvider<JavaMailSender> sender, @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Async
    public void send(String to, String subject, String body) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) { log.warn("No mail sender configured, skipping mail to {}", to); return; }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            s.send(m);
        } catch (Exception e) {
            log.warn("Could not send mail to {}: {}", to, e.getMessage());
        }
    }
}
