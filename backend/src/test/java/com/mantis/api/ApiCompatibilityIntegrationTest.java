package com.mantis.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mantis.domain.Category;
import com.mantis.domain.Project;
import com.mantis.domain.ProjectUser;
import com.mantis.domain.User;
import com.mantis.repo.CategoryRepository;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.ProjectUserRepository;
import com.mantis.repo.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests for the Mantis REST / SOAP compatibility layer.
 *
 * <p>Uses a real Spring context and an in-memory H2 database (PostgreSQL compatibility mode) so the
 * security filters, JPA mappings, transactions and permission rules are all exercised. Real users
 * with different access levels are created and authenticated through the normal login endpoint.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiCompatibilityIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:api-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired CategoryRepository categories;
    @Autowired ProjectUserRepository members;
    @Autowired PasswordEncoder encoder;

    String adminToken, reporterToken, reporter2Token, devToken, managerToken;
    Long projectId, privateProjectId, categoryId;
    Long publicIssueId, privateIssueId;
    Long devUserId;

    // ================================================================== fixtures

    @BeforeAll
    void setUp() throws Exception {
        adminToken = login("administrator", "root");

        Project sample = projects.findAllByOrderByName().stream()
                .filter(p -> "Sample Project".equals(p.getName())).findFirst().orElseThrow();
        projectId = sample.getId();
        Category category = categories.findByProjectIdOrderByName(projectId).get(0);
        categoryId = category.getId();

        user("reporter1", 25);
        user("reporter2", 25);
        user("dev1", 55);
        user("manager1", 70);
        user("outsider1", 25);
        devUserId = users.findByUsername("dev1").orElseThrow().getId();

        reporterToken = login("reporter1", "secret1");
        reporter2Token = login("reporter2", "secret1");
        devToken = login("dev1", "secret1");
        managerToken = login("manager1", "secret1");

        Project secret = new Project();
        secret.setName("Secret Project");
        secret.setViewState(50);
        secret.setDescription("private project");
        projects.save(secret);
        privateProjectId = secret.getId();

        publicIssueId = createIssue(reporterToken, projectId, "Public issue", null);
        privateIssueId = createIssue(reporterToken, projectId, "Private issue", 50);

        // a private note may only be created by a developer+ user
        addNote(devToken, publicIssueId, "private note from dev", 50);
    }

    private User user(String username, int level) {
        return users.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setRealname(username);
            u.setEmail(username + "@example.com");
            u.setPassword(encoder.encode("secret1"));
            u.setAccessLevel(level);
            return users.save(u);
        });
    }

    private String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private Long createIssue(String token, Long projectId, String summary, Integer viewState) throws Exception {
        String vs = viewState == null ? "" : ",\"view_state\":{\"id\":" + viewState + "}";
        MvcResult r = mvc.perform(post("/api/rest/issues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":{\"id\":" + projectId + "},\"category\":{\"id\":" + categoryId + "},"
                                + "\"summary\":\"" + summary + "\",\"description\":\"details\","
                                + "\"priority\":{\"id\":30}" + vs + "}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long addNote(String token, Long issueId, String text, Integer viewState) throws Exception {
        String vs = viewState == null ? "" : ",\"view_state\":{\"id\":" + viewState + "}";
        MvcResult r = mvc.perform(post("/api/rest/issues/" + issueId + "/notes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"" + vs + "}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    // ================================================================== authentication / tokens

    @Test
    void restRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/rest/issues")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rest/issues").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiTokenAuthenticatesAndCanBeRevoked() throws Exception {
        MvcResult created = mvc.perform(post("/api/tokens")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"integration test\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String raw = body.get("token").asText();
        long id = body.get("details").get("id").asLong();
        assertThat(raw).hasSize(48);

        // both "Bearer <token>" and the bare token are accepted
        mvc.perform(get("/api/rest/users/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("administrator"));
        mvc.perform(get("/api/rest/users/me").header("Authorization", raw))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/tokens/" + id).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rest/users/me").header("Authorization", "Bearer " + raw))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenListNeverExposesTheSecret() throws Exception {
        mvc.perform(post("/api/tokens")
                        .header("Authorization", "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mine\"}"))
                .andExpect(status().isCreated());

        String list = mvc.perform(get("/api/tokens").header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("\"token\":\"");
        assertThat(list).contains("mine");
    }

    // ================================================================== permissions

    @Test
    void privateProjectIsHiddenFromNonMembers() throws Exception {
        mvc.perform(get("/api/rest/projects/" + privateProjectId).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Secret Project"));

        mvc.perform(get("/api/rest/projects/" + privateProjectId).header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateIssueIsVisibleOnlyToItsReporterAndDevelopers() throws Exception {
        mvc.perform(get("/api/rest/issues/" + privateIssueId).header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rest/issues/" + privateIssueId).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rest/issues/" + privateIssueId).header("Authorization", "Bearer " + reporter2Token))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateNoteIsHiddenFromOtherReporters() throws Exception {
        String other = mvc.perform(get("/api/rest/issues/" + publicIssueId + "/notes")
                        .header("Authorization", "Bearer " + reporter2Token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(other).doesNotContain("private note from dev");

        String developer = mvc.perform(get("/api/rest/issues/" + publicIssueId + "/notes")
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(developer).contains("private note from dev");
    }

    @Test
    void reporterCannotUpdateOrDeleteIssues() throws Exception {
        mvc.perform(patch("/api/rest/issues/" + publicIssueId)
                        .header("Authorization", "Bearer " + reporter2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"hijacked\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/rest/issues/" + publicIssueId)
                        .header("Authorization", "Bearer " + reporter2Token))
                .andExpect(status().isForbidden());

        JsonNode issue = getJson(reporterToken, "/api/rest/issues/" + publicIssueId);
        assertThat(issue.get("summary").asText()).isEqualTo("Public issue");
    }

    @Test
    void developerCanResolveAndManagerCanReopen() throws Exception {
        Long id = createIssue(reporterToken, projectId, "workflow issue", null);

        mvc.perform(patch("/api/rest/issues/" + id)
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":{\"id\":80},\"resolution\":{\"id\":20}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.id").value(80))
                .andExpect(jsonPath("$.resolution.id").value(20));

        // resolved issues are read-only for non-managers
        mvc.perform(patch("/api/rest/issues/" + id)
                        .header("Authorization", "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"late edit\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/rest/issues/" + id)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":{\"id\":50}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.id").value(50));
    }

    // ================================================================== issue CRUD / pagination

    @Test
    void issueCrudRoundTrip() throws Exception {
        MvcResult created = mvc.perform(post("/api/rest/issues")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":{\"id\":" + projectId + "},\"category\":{\"id\":" + categoryId + "},"
                                + "\"summary\":\"crud issue\",\"description\":\"first\",\"priority\":{\"id\":30}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary").value("crud issue"))
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(patch("/api/rest/issues/" + id)
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"crud issue updated\",\"priority\":{\"name\":\"high\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("crud issue updated"))
                .andExpect(jsonPath("$.priority.id").value(40));

        mvc.perform(delete("/api/rest/issues/" + id).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rest/issues/" + id).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void enumsAcceptNumericIdAndName() throws Exception {
        MvcResult created = mvc.perform(post("/api/rest/issues")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":{\"id\":" + projectId + "},\"summary\":\"enum issue\","
                                + "\"description\":\"d\",\"priority\":{\"id\":40},\"severity\":{\"name\":\"major\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority.id").value(40))
                .andExpect(jsonPath("$.priority.name").value("high"))
                .andExpect(jsonPath("$.severity.id").value(60))
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(delete("/api/rest/issues/" + id).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void paginationAndSorting() throws Exception {
        for (int i = 0; i < 3; i++) createIssue(reporterToken, projectId, "page issue " + i, null);

        MvcResult first = mvc.perform(get("/api/rest/issues?page=1&page_size=2&sort=id&dir=asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.page_size").value(2))
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andReturn();
        JsonNode one = json.readTree(first.getResponse().getContentAsString());
        assertThat(one.get("total_count").asLong()).isGreaterThanOrEqualTo(5);

        MvcResult second = mvc.perform(get("/api/rest/issues?page=2&page_size=2&sort=id&dir=asc")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode two = json.readTree(second.getResponse().getContentAsString());
        assertThat(two.get("issues").get(0).get("id").asLong())
                .isGreaterThan(one.get("issues").get(1).get("id").asLong());
    }

    @Test
    void invalidRequestsReturnStructuredErrors() throws Exception {
        mvc.perform(post("/api/rest/issues")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"project\":{\"id\":" + projectId + "},\"summary\":\"no description\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").exists());

        mvc.perform(get("/api/rest/issues/not-a-number").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(get("/api/rest/issues/999999").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ================================================================== notes / attachments

    @Test
    void notesCanBeAddedAndListed() throws Exception {
        Long id = createIssue(reporterToken, projectId, "note issue", null);
        addNote(reporterToken, id, "a public note", null);

        JsonNode notes = getJson(reporterToken, "/api/rest/issues/" + id + "/notes");
        assertThat(notes.isArray()).isTrue();
        assertThat(notes.toString()).contains("a public note");
    }

    @Test
    void attachmentUploadRespectsConfiguredLimit() throws Exception {
        Long id = createIssue(reporterToken, projectId, "attachment issue", null);
        MockMultipartFile small = new MockMultipartFile("file", "hello.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
        MvcResult uploaded = mvc.perform(multipart("/api/rest/issues/" + id + "/files").file(small)
                        .header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("hello.txt"))
                .andReturn();
        long fileId = json.readTree(uploaded.getResponse().getContentAsString()).get("id").asLong();

        JsonNode files = getJson(reporterToken, "/api/rest/issues/" + id + "/files");
        assertThat(files.toString()).contains("hello.txt");

        mvc.perform(get("/api/rest/issues/" + id + "/files/" + fileId)
                        .header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk())
                .andExpect(content().bytes("hello".getBytes(StandardCharsets.UTF_8)));

        byte[] tooBig = new byte[2048];               // test profile sets the limit to 1024 bytes
        MockMultipartFile large = new MockMultipartFile("file", "big.bin", "application/octet-stream", tooBig);
        mvc.perform(multipart("/api/rest/issues/" + id + "/files").file(large)
                        .header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isBadRequest());
    }

    // ================================================================== tags / relationships / monitors

    @Test
    void tagsRelationshipsAndMonitorsWork() throws Exception {
        Long a = createIssue(devToken, projectId, "rel A", null);
        Long b = createIssue(devToken, projectId, "rel B", null);

        MvcResult tag = mvc.perform(post("/api/tags")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"api-tag\"}"))
                .andExpect(status().isOk()).andReturn();
        long tagId = json.readTree(tag.getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/rest/issues/" + a + "/tags")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tag\":{\"name\":\"api-tag\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/rest/issues/" + a + "/tags").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("api-tag"));
        mvc.perform(delete("/api/rest/issues/" + a + "/tags/" + tagId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());

        MvcResult rel = mvc.perform(post("/api/rest/issues/" + a + "/relationships")
                        .header("Authorization", "Bearer " + devToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":{\"id\":1},\"issue\":{\"id\":" + b + "}}"))
                .andExpect(status().isCreated()).andReturn();
        long relId = json.readTree(rel.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/rest/issues/" + a + "/relationships").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].issue.id").value(b));
        mvc.perform(delete("/api/rest/issues/" + a + "/relationships/" + relId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/rest/issues/" + a + "/monitors").header("Authorization", "Bearer " + devToken))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/rest/issues/" + a + "/monitors/" + devUserId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/rest/issues/" + a).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/rest/issues/" + b).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
    }

    // ================================================================== SOAP

    private String soap(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/soap")
                        .contentType(MediaType.TEXT_XML)
                        .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                                + " xmlns:ns=\"http://futureware.biz/mantisconnect\">"
                                + "<soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>"))
                .andReturn();
        return r.getResponse().getContentAsString();
    }

    private MvcResult soapResult(String body) throws Exception {
        return mvc.perform(post("/api/soap")
                        .contentType(MediaType.TEXT_XML)
                        .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                                + " xmlns:ns=\"http://futureware.biz/mantisconnect\">"
                                + "<soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>"))
                .andReturn();
    }

    @Test
    void soapVersionIsPublic() throws Exception {
        String response = soap("<ns:mc_version/>");
        assertThat(response).contains("1.0.0-mantis-java");
    }

    @Test
    void soapLoginAndInvalidCredentials() throws Exception {
        String ok = soap("<ns:mc_login><username>administrator</username><password>root</password></ns:mc_login>");
        assertThat(ok).contains("<name>administrator</name>").contains("<access_level>90</access_level>");

        MvcResult bad = soapResult("<ns:mc_login><username>administrator</username><password>wrong</password></ns:mc_login>");
        assertThat(bad.getResponse().getStatus()).isEqualTo(500);
        assertThat(bad.getResponse().getContentAsString()).contains("Access denied");
    }

    @Test
    void soapIssueGetRespectsPermissions() throws Exception {
        String ok = soap("<ns:mc_issue_get><username>reporter1</username><password>secret1</password>"
                + "<issue_id>" + privateIssueId + "</issue_id></ns:mc_issue_get>");
        assertThat(ok).contains("<summary>Private issue</summary>");

        MvcResult denied = soapResult("<ns:mc_issue_get><username>reporter2</username><password>secret1</password>"
                + "<issue_id>" + privateIssueId + "</issue_id></ns:mc_issue_get>");
        assertThat(denied.getResponse().getStatus()).isEqualTo(500);
        assertThat(denied.getResponse().getContentAsString()).contains("Issue not found");
    }

    @Test
    void soapIssueAddNoteAddAndRead() throws Exception {
        MvcResult added = soapResult("<ns:mc_issue_add><username>reporter1</username><password>secret1</password>"
                + "<issue><project><id>" + projectId + "</id></project>"
                + "<category><id>" + categoryId + "</id></category>"
                + "<summary>soap issue</summary><description>from soap</description></issue></ns:mc_issue_add>");
        assertThat(added.getResponse().getStatus()).isEqualTo(200);
        long issueId = extractReturnLong(added.getResponse().getContentAsString());
        assertThat(issueId).isPositive();

        MvcResult note = soapResult("<ns:mc_issue_note_add><username>reporter1</username><password>secret1</password>"
                + "<issue_id>" + issueId + "</issue_id>"
                + "<note><text>soap note</text></note></ns:mc_issue_note_add>");
        assertThat(note.getResponse().getStatus()).isEqualTo(200);
        assertThat(extractReturnLong(note.getResponse().getContentAsString())).isPositive();

        String notes = soap("<ns:mc_issue_notes_get><username>reporter1</username><password>secret1</password>"
                + "<issue_id>" + issueId + "</issue_id></ns:mc_issue_notes_get>");
        assertThat(notes).contains("soap note");

        mvc.perform(delete("/api/rest/issues/" + issueId).header("Authorization", "Bearer " + devToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void soapUpdateAndDelete() throws Exception {
        Long id = createIssue(reporterToken, projectId, "soap update issue", null);
        String updated = soap("<ns:mc_issue_update><username>dev1</username><password>secret1</password>"
                + "<issue_id>" + id + "</issue_id>"
                + "<issue><summary>soap updated</summary></issue></ns:mc_issue_update>");
        assertThat(updated).contains("<return>true</return>");

        String deleted = soap("<ns:mc_issue_delete><username>dev1</username><password>secret1</password>"
                + "<issue_id>" + id + "</issue_id></ns:mc_issue_delete>");
        assertThat(deleted).contains("<return>true</return>");
    }

    @Test
    void soapUnsupportedOperationReturnsFault() throws Exception {
        MvcResult r = soapResult("<ns:mc_not_implemented><username>administrator</username>"
                + "<password>root</password></ns:mc_not_implemented>");
        assertThat(r.getResponse().getStatus()).isEqualTo(500);
        assertThat(r.getResponse().getContentAsString()).contains("Unsupported MantisConnect operation");
    }

    @Test
    void soapRejectsMalformedAndXxeRequests() throws Exception {
        MvcResult malformed = mvc.perform(post("/api/soap")
                        .contentType(MediaType.TEXT_XML).content("<not-xml"))
                .andReturn();
        assertThat(malformed.getResponse().getStatus()).isEqualTo(500);
        assertThat(malformed.getResponse().getContentAsString()).contains("Malformed SOAP request");

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:ns=\"http://futureware.biz/mantisconnect\"><soapenv:Body>"
                + "<ns:mc_login><username>&xxe;</username><password>x</password></ns:mc_login>"
                + "</soapenv:Body></soapenv:Envelope>";
        MvcResult blocked = mvc.perform(post("/api/soap").contentType(MediaType.TEXT_XML).content(xxe))
                .andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(500);
        assertThat(blocked.getResponse().getContentAsString()).contains("Malformed SOAP request");
    }

    @Test
    void wsdlIsServed() throws Exception {
        mvc.perform(get("/api/soap/mantisconnect.wsdl"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MantisConnectBinding")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mc_issue_get")));
    }

    private static long extractReturnLong(String xml) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<return>(\\d+)</return>").matcher(xml);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }
}
