package com.mantis.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mantis.domain.Issue;
import com.mantis.domain.IssueMonitor;
import com.mantis.domain.Project;
import com.mantis.domain.ProjectVersion;
import com.mantis.domain.User;
import com.mantis.repo.IssueRepository;
import com.mantis.repo.MonitorRepository;
import com.mantis.repo.ProjectRepository;
import com.mantis.repo.UserRepository;
import com.mantis.repo.VersionRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap, changelog and My View: real Spring context + H2, users with different access levels.
 * The main point: the pages use the normal search rules, so a private issue never shows up for somebody who may not see it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:overview-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired VersionRepository versions;
    @Autowired MonitorRepository monitors;
    @Autowired PasswordEncoder encoder;

    String devToken, rep1Token, rep2Token;
    Long projectId;
    Long openPlanned, resolvedPlanned, closedFixed, privatePlanned, unassignedNoTarget;

    @BeforeAll
    void setUp() throws Exception {
        Project p = projects.findAllByOrderByName().stream().filter(x -> "Sample Project".equals(x.getName())).findFirst().orElseThrow();
        projectId = p.getId();
        User dev = user("dev1", 55), rep1 = user("rep1", 25), rep2 = user("rep2", 25);

        version(p, "1.0", true, Instant.parse("2026-01-01T00:00:00Z"));
        version(p, "2.0", false, Instant.parse("2026-06-01T00:00:00Z"));
        version(p, "3.0", false, Instant.parse("2026-09-01T00:00:00Z"));          // no issues: must not be listed

        openPlanned = issue(p, rep1, dev, "open planned", 50, 10, "2.0", "");
        resolvedPlanned = issue(p, rep1, dev, "resolved planned", 80, 10, "2.0", "");
        closedFixed = issue(p, rep1, dev, "closed fixed", 90, 10, "", "1.0");
        privatePlanned = issue(p, rep2, null, "private planned", 10, 50, "2.0", "");
        unassignedNoTarget = issue(p, rep1, null, "unassigned", 10, 10, "", "");

        devToken = login("dev1");
        rep1Token = login("rep1");
        rep2Token = login("rep2");
    }

    private User user(String name, int level) {
        return users.findByUsername(name).orElseGet(() -> {
            User u = new User();
            u.setUsername(name); u.setRealname(name); u.setEmail(name + "@example.com");
            u.setPassword(encoder.encode("secret1")); u.setAccessLevel(level);
            return users.save(u);
        });
    }

    private void version(Project p, String name, boolean released, Instant date) {
        ProjectVersion v = new ProjectVersion();
        v.setProject(p); v.setVersion(name); v.setReleased(released); v.setDateOrder(date);
        versions.save(v);
    }

    private Long issue(Project p, User reporter, User handler, String summary, int status, int viewState, String target, String fixedIn) {
        Issue i = new Issue();
        i.setProject(p); i.setReporter(reporter); i.setHandler(handler); i.setSummary(summary); i.setDescription("details");
        i.setStatus(status); i.setViewState(viewState); i.setTargetVersion(target); i.setFixedInVersion(fixedIn);
        return issues.save(i).getId();
    }

    private String login(String username) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"secret1\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        MvcResult r = mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode issues) {
        List<Long> out = new ArrayList<>();
        issues.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private static JsonNode box(JsonNode myView, String key) {
        for (JsonNode b : myView.get("boxes")) if (key.equals(b.get("key").asText())) return b;
        return null;
    }

    // ================================================================== roadmap

    @Test
    void roadmapListsUnreleasedVersionsWithPlannedIssuesAndProgress() throws Exception {
        JsonNode r = getJson(devToken, "/api/roadmap?projectId=" + projectId);
        assertThat(r.get("versions")).hasSize(1);                       // 1.0 is released, 3.0 has no issues
        JsonNode v = r.get("versions").get(0);
        assertThat(v.get("version").asText()).isEqualTo("2.0");
        assertThat(v.get("total").asInt()).isEqualTo(3);                // developer also sees the private issue
        assertThat(v.get("resolved").asInt()).isEqualTo(1);
        assertThat(ids(v.get("issues"))).containsExactly(openPlanned, privatePlanned, resolvedPlanned);   // open first, resolved last
        assertThat(r.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void roadmapNeverShowsPrivateIssuesToOtherReporters() throws Exception {
        JsonNode other = getJson(rep1Token, "/api/roadmap?projectId=" + projectId).get("versions").get(0);
        assertThat(ids(other.get("issues"))).containsExactlyInAnyOrder(openPlanned, resolvedPlanned).doesNotContain(privatePlanned);
        assertThat(other.get("total").asInt()).isEqualTo(2);

        JsonNode owner = getJson(rep2Token, "/api/roadmap?projectId=" + projectId).get("versions").get(0);
        assertThat(ids(owner.get("issues"))).contains(privatePlanned);  // the reporter of a private issue sees it
    }

    @Test
    void roadmapWithoutProjectCoversAllVisibleProjects() throws Exception {
        assertThat(getJson(devToken, "/api/roadmap").get("versions")).hasSize(1);
    }

    @Test
    void unknownProjectIsNotFound() throws Exception {
        mvc.perform(get("/api/roadmap?projectId=999999").header("Authorization", "Bearer " + devToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/changelog?projectId=999999").header("Authorization", "Bearer " + devToken)).andExpect(status().isNotFound());
    }

    // ================================================================== changelog

    @Test
    void changelogListsResolvedIssuesByFixedInVersion() throws Exception {
        JsonNode r = getJson(devToken, "/api/changelog?projectId=" + projectId);
        assertThat(r.get("versions")).hasSize(1);
        JsonNode v = r.get("versions").get(0);
        assertThat(v.get("version").asText()).isEqualTo("1.0");
        assertThat(v.get("released").asBoolean()).isTrue();
        assertThat(ids(v.get("issues"))).containsExactly(closedFixed);
    }

    // ================================================================== my view

    @Test
    void myViewOfADeveloper() throws Exception {
        JsonNode view = getJson(devToken, "/api/my-view");
        assertThat(ids(box(view, "assigned").get("items"))).containsExactlyInAnyOrder(openPlanned);
        assertThat(box(view, "assigned").get("total").asInt()).isEqualTo(1);
        assertThat(ids(box(view, "unassigned").get("items"))).containsExactlyInAnyOrder(privatePlanned, unassignedNoTarget);
        assertThat(box(view, "recent").get("total").asInt()).isEqualTo(5);
        assertThat(box(view, "reported").get("total").asInt()).isZero();
    }

    @Test
    void myViewOfAReporterHidesPrivateIssuesOfOthersAndHasNoUnassignedBox() throws Exception {
        JsonNode view = getJson(rep1Token, "/api/my-view");
        assertThat(box(view, "unassigned")).isNull();                   // only people who can assign get this box
        assertThat(ids(box(view, "reported").get("items"))).containsExactlyInAnyOrder(openPlanned, unassignedNoTarget);   // unresolved only
        assertThat(ids(box(view, "verify").get("items"))).containsExactly(resolvedPlanned);   // resolved, waiting for verification
        assertThat(box(view, "recent").get("total").asInt()).isEqualTo(4);                    // not the private issue of rep2
    }

    @Test
    void monitoredBoxListsWhatIWatch() throws Exception {
        User dev = users.findByUsername("dev1").orElseThrow();
        IssueMonitor m = new IssueMonitor();
        m.setUser(dev); m.setIssue(issues.findById(unassignedNoTarget).orElseThrow());
        monitors.save(m);
        try {
            JsonNode view = getJson(devToken, "/api/my-view?projectId=" + projectId);
            assertThat(ids(box(view, "monitored").get("items"))).containsExactly(unassignedNoTarget);
            assertThat(box(getJson(rep1Token, "/api/my-view"), "monitored").get("total").asInt()).isZero();
        } finally {
            monitors.delete(m);
        }
    }

    @Test
    void pagesNeedAuthentication() throws Exception {
        mvc.perform(get("/api/my-view")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/roadmap")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/changelog")).andExpect(status().isUnauthorized());
    }
}
