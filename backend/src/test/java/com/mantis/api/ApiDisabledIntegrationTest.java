package com.mantis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the environment toggles: when REST/SOAP/token auth are disabled the endpoints must not be
 * reachable, even for a fully authenticated administrator.
 */
@SpringBootTest(properties = {
        "app.api.rest.enabled=false",
        "app.api.soap.enabled=false",
        "app.api.token.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiDisabledIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:disabled-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"administrator\",\"password\":\"root\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void restEndpointIsNotExposed() throws Exception {
        String token = login();
        mvc.perform(get("/api/rest/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void soapEndpointIsNotExposed() throws Exception {
        mvc.perform(post("/api/soap")
                        .contentType(MediaType.TEXT_XML)
                        .content("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<soapenv:Body><mc_version xmlns=\"http://futureware.biz/mantisconnect\"/>"
                                + "</soapenv:Body></soapenv:Envelope>"))
                .andExpect(status().isNotFound());
    }

    @Test
    void apiTokensAreNotAcceptedWhenTokenAuthIsDisabled() throws Exception {
        String token = login();
        MvcResult created = mvc.perform(post("/api/tokens")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"disabled-auth\"}"))
                .andExpect(status().isCreated()).andReturn();
        String raw = json.readTree(created.getResponse().getContentAsString()).get("token").asText();
        assertThat(raw).isNotBlank();

        mvc.perform(get("/api/rest/users/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isUnauthorized());
    }
}
