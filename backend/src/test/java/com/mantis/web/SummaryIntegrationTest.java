package com.mantis.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mantis.domain.Category;
import com.mantis.domain.Issue;
import com.mantis.domain.IssueHistory;
import com.mantis.domain.Note;
import com.mantis.domain.Project;
import com.mantis.domain.User;
import com.mantis.repo.CategoryRepository;
import com.mantis.repo.HistoryRepository;
import com.mantis.repo.IssueRepository;
import com.mantis.repo.NoteRepository;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Summary report with hand-computed numbers. No data point lies on a time-window boundary (offsets such as 60 hours),
 * so the test is stable whatever the clock says. A private issue must not be counted for people who may not see it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:summary-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired CategoryRepository categories;
    @Autowired IssueRepository issues;
    @Autowired HistoryRepository history;
    @Autowired NoteRepository notes;
    @Autowired PasswordEncoder encoder;

    String devToken, rep1Token;
    Long projectId, i1, i2, i3, i4, i5;

    /*
     * i1  rep1 -> dev1  status 50 (assigned)  General   submitted 60 h ago        open
     * i2  rep1 -> dev1  status 10 (new)       Billing   submitted 10 days ago     open
     * i3  rep2 -> nobody status 10 (new)      none      submitted 40 days ago     open, PRIVATE
     * i4  rep2 -> dev1  status 80 (resolved)  General   submitted 400 days ago    history: resolved 60 h ago
     * i5  rep1 -> dev1  status 90 (closed)    Billing   submitted 45 days ago     history: closed 45 days ago
     * notes (public): i1 x3, i2 x1, i3 x5;  i4 has 2 PRIVATE notes (must not count)
     */
    @BeforeAll
    void setUp() throws Exception {
        Project p = projects.findAllByOrderByName().stream().filter(x -> "Sample Project".equals(x.getName())).findFirst().orElseThrow();
        projectId = p.getId();
        User dev = user("dev1", 55), rep1 = user("rep1", 25), rep2 = user("rep2", 25);
        Category general = categories.findByProjectIdOrderByName(projectId).stream().filter(c -> "General".equals(c.getName())).findFirst().orElseThrow();
        Category billing = new Category();
        billing.setProject(p); billing.setName("Billing");
        categories.save(billing);

        Instant now = Instant.now();
        i1 = issue(p, rep1, dev, 50, 10, general, now.minus(Duration.ofHours(60)));
        i2 = issue(p, rep1, dev, 10, 10, billing, now.minus(Duration.ofDays(10)));
        i3 = issue(p, rep2, null, 10, 50, null, now.minus(Duration.ofDays(40)));
        i4 = issue(p, rep2, dev, 80, 10, general, now.minus(Duration.ofDays(400)));
        i5 = issue(p, rep1, dev, 90, 10, billing, now.minus(Duration.ofDays(45)));

        statusChange(i4, "80", now.minus(Duration.ofHours(60)));
        statusChange(i5, "90", now.minus(Duration.ofDays(45)));

        for (int n = 0; n < 3; n++) note(i1, rep1, 10);
        note(i2, rep1, 10);
        for (int n = 0; n < 5; n++) note(i3, rep2, 10);
        note(i4, dev, 50); note(i4, dev, 50);

        devToken = login("dev1");
        rep1Token = login("rep1");
    }

    private User user(String name, int level) {
        return users.findByUsername(name).orElseGet(() -> {
            User u = new User();
            u.setUsername(name); u.setRealname(name); u.setEmail(name + "@example.com");
            u.setPassword(encoder.encode("secret1")); u.setAccessLevel(level);
            return users.save(u);
        });
    }

    private Long issue(Project p, User reporter, User handler, int status, int viewState, Category category, Instant submitted) {
        Issue i = new Issue();
        i.setProject(p); i.setReporter(reporter); i.setHandler(handler); i.setSummary("issue " + status + " " + submitted);
        i.setDescription("details"); i.setStatus(status); i.setViewState(viewState); i.setCategory(category);
        i.setDateSubmitted(submitted);
        return issues.save(i).getId();
    }

    private void statusChange(Long issueId, String newValue, Instant when) {
        IssueHistory h = new IssueHistory();
        h.setIssue(issues.findById(issueId).orElseThrow()); h.setFieldName("status"); h.setNewValue(newValue); h.setDateModified(when);
        history.save(h);
    }

    private void note(Long issueId, User by, int viewState) {
        Note n = new Note();
        n.setIssue(issues.findById(issueId).orElseThrow()); n.setReporter(by); n.setText("note"); n.setViewState(viewState);
        notes.save(n);
    }

    private String login(String username) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode report(String token, String query) throws Exception {
        MvcResult r = mvc.perform(get("/api/reports/summary" + query).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** the row of a list whose "label" (or "name") equals the given text */
    private static JsonNode row(JsonNode list, String field, String value) {
        for (JsonNode n : list) if (value.equals(n.get(field).asText())) return n;
        throw new AssertionError("no row with " + field + "=" + value + " in " + list);
    }

    private static List<Long> longs(JsonNode list, String field) {
        List<Long> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get(field).asLong()));
        return out;
    }

    // ================================================================== a developer sees everything

    @Test
    void totalsAndStatusTable() throws Exception {
        JsonNode r = report(devToken, "?projectId=" + projectId);
        assertThat(r.get("total").asInt()).isEqualTo(5);
        assertThat(r.get("open").asInt()).isEqualTo(3);
        assertThat(r.get("resolved").asInt()).isEqualTo(1);
        assertThat(r.get("closed").asInt()).isEqualTo(1);
        JsonNode newIssues = row(r.get("byStatus"), "key", "10");
        assertThat(newIssues.get("total").asInt()).isEqualTo(2);
        assertThat(newIssues.get("open").asInt()).isEqualTo(2);
        assertThat(row(r.get("byStatus"), "key", "80").get("open").asInt()).isZero();
        assertThat(row(r.get("byStatus"), "key", "50").get("label").asText()).isEqualTo("assigned");
    }

    @Test
    void categoriesAreGroupedByName() throws Exception {
        JsonNode c = report(devToken, "?projectId=" + projectId).get("byCategory");
        assertThat(row(c, "label", "General").get("total").asInt()).isEqualTo(2);
        assertThat(row(c, "label", "General").get("open").asInt()).isEqualTo(1);
        assertThat(row(c, "label", "Billing").get("total").asInt()).isEqualTo(2);
        assertThat(row(c, "label", "(no category)").get("total").asInt()).isEqualTo(1);
    }

    @Test
    void developersAndReporters() throws Exception {
        JsonNode r = report(devToken, "?projectId=" + projectId);
        JsonNode dev = row(r.get("byHandler"), "name", "dev1");
        assertThat(dev.get("total").asInt()).isEqualTo(4);
        assertThat(dev.get("open").asInt()).isEqualTo(2);
        assertThat(dev.get("resolved").asInt()).isEqualTo(1);
        assertThat(dev.get("closed").asInt()).isEqualTo(1);
        assertThat(row(r.get("byHandler"), "name", "[nobody]").get("total").asInt()).isEqualTo(1);

        JsonNode rep1 = row(r.get("byReporter"), "name", "rep1");
        assertThat(rep1.get("total").asInt()).isEqualTo(3);
        assertThat(rep1.get("open").asInt()).isEqualTo(2);
        assertThat(rep1.get("closed").asInt()).isEqualTo(1);
        JsonNode rep2 = row(r.get("byReporter"), "name", "rep2");
        assertThat(rep2.get("total").asInt()).isEqualTo(2);
        assertThat(rep2.get("resolved").asInt()).isEqualTo(1);
    }

    @Test
    void openedAndResolvedPerWindow() throws Exception {
        JsonNode by = report(devToken, "?projectId=" + projectId).get("byDate");
        int[] days = {1, 2, 3, 7, 30, 60, 90, 180, 365};
        int[] opened = {0, 0, 1, 1, 2, 4, 4, 4, 4};        // i1 (60 h), i2 (10 d), i3 (40 d), i5 (45 d); i4 is 400 days old
        int[] resolved = {0, 0, 1, 1, 1, 2, 2, 2, 2};       // i4 resolved 60 h ago, i5 closed 45 days ago (from the history)
        assertThat(by).hasSize(days.length);
        for (int k = 0; k < days.length; k++) {
            JsonNode d = row(by, "days", String.valueOf(days[k]));
            assertThat(d.get("opened").asInt()).as("opened in %d days", days[k]).isEqualTo(opened[k]);
            assertThat(d.get("resolved").asInt()).as("resolved in %d days", days[k]).isEqualTo(resolved[k]);
        }
    }

    @Test
    void ageOfOpenIssues() throws Exception {
        JsonNode age = report(devToken, "?projectId=" + projectId).get("openAge");
        assertThat(age).hasSize(6);
        assertThat(row(age, "label", "less than 1 week").get("count").asInt()).isEqualTo(1);     // i1
        assertThat(row(age, "label", "1 week to 1 month").get("count").asInt()).isEqualTo(1);    // i2
        assertThat(row(age, "label", "1 to 3 months").get("count").asInt()).isEqualTo(1);        // i3
        assertThat(row(age, "label", "1 year or older").get("count").asInt()).isZero();          // i4 is resolved, not open
    }

    @Test
    void longestOpenAndMostActive() throws Exception {
        JsonNode r = report(devToken, "?projectId=" + projectId);
        assertThat(longs(r.get("longestOpen"), "id")).containsExactly(i3, i2, i1);
        assertThat(r.get("longestOpen").get(0).get("ageDays").asInt()).isGreaterThanOrEqualTo(40);
        assertThat(longs(r.get("mostActive"), "id")).containsExactly(i3, i1, i2);    // i4 has only private notes
        assertThat(r.get("mostActive").get(0).get("notes").asInt()).isEqualTo(5);
    }

    // ================================================================== visibility

    @Test
    void aReporterDoesNotSeeOrCountAPrivateIssueOfSomebodyElse() throws Exception {
        JsonNode r = report(rep1Token, "?projectId=" + projectId);
        assertThat(r.get("total").asInt()).isEqualTo(4);                                  // not i3
        assertThat(r.get("open").asInt()).isEqualTo(2);
        assertThat(longs(r.get("longestOpen"), "id")).containsExactly(i2, i1).doesNotContain(i3);
        assertThat(longs(r.get("mostActive"), "id")).containsExactly(i1, i2).doesNotContain(i3);
        assertThat(r.get("byHandler").toString()).doesNotContain("[nobody]");             // the only unassigned issue is the private one
        assertThat(row(r.get("byReporter"), "name", "rep1").get("total").asInt()).isEqualTo(3);
    }

    @Test
    void allProjectsAndUnknownProject() throws Exception {
        assertThat(report(devToken, "").get("total").asInt()).isEqualTo(5);
        assertThat(report(devToken, "").get("byProject")).hasSize(1);
        mvc.perform(get("/api/reports/summary?projectId=999999").header("Authorization", "Bearer " + devToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/reports/summary")).andExpect(status().isUnauthorized());
    }
}
