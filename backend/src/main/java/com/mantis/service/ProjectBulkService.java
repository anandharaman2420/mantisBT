package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;
import com.mantis.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bulk project setup: hundreds of categories (e.g. one per form), versions and members in one request,
 * or copy the setup of another project. Everything runs in ONE transaction: a request either applies completely
 * or (on an unexpected error) not at all. Rows with problems (unknown user, too long, duplicate ...) do not abort the
 * request - they are skipped and reported back row by row.
 */
@Service
@Transactional
public class ProjectBulkService {
    private static final int MAX_PROBLEMS = 200;

    private final AccessService access;
    private final CategoryRepository categories;
    private final VersionRepository versions;
    private final ProjectUserRepository members;
    private final UserRepository users;
    private final CustomFieldProjectRepository fieldLinks;
    private final PasswordEncoder encoder;

    public ProjectBulkService(AccessService access, CategoryRepository categories, VersionRepository versions,
                              ProjectUserRepository members, UserRepository users, CustomFieldProjectRepository fieldLinks,
                              PasswordEncoder encoder) {
        this.access = access; this.categories = categories; this.versions = versions; this.members = members;
        this.users = users; this.fieldLinks = fieldLinks; this.encoder = encoder;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

    private static void problem(List<RowMessage> list, int row, String value, String msg) {
        if (list.size() < MAX_PROBLEMS) list.add(new RowMessage(row, value, msg));
    }

    // ================================================================== categories
    public ImportResult categories(User me, Long pid, List<BulkCategoryRow> rows) {
        Project p = access.projectOrThrow(pid);
        access.require(me, p, Lookups.MANAGER);

        Map<String, Category> own = new HashMap<>();
        categories.findByProjectIdOrderByName(pid).forEach(c -> own.putIfAbsent(key(c.getName()), c));
        Set<String> inherited = new HashSet<>();
        for (Project a : access.inheritedAncestors(p))
            categories.findByProjectIdOrderByName(a.getId()).forEach(c -> inherited.add(key(c.getName())));
        Map<String, User> byName = new HashMap<>();
        users.findAll().forEach(u -> byName.put(key(u.getUsername()), u));
        Map<Long, Boolean> isDev = new HashMap<>();

        List<Category> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<RowMessage> problems = new ArrayList<>();
        int created = 0, updated = 0, skipped = 0, rowNo = 0;
        for (BulkCategoryRow r : rows) {
            rowNo++;
            String name = r.name() == null ? "" : r.name().trim().replaceAll("\\s+", " ");
            if (name.isEmpty()) { skipped++; continue; }
            if (name.length() > 128) { skipped++; problem(problems, rowNo, name.substring(0, 40) + "…", "name longer than 128 characters"); continue; }
            String k = key(name);
            if (!seen.add(k)) { skipped++; problem(problems, rowNo, name, "duplicate inside the list"); continue; }

            User handler = null;
            if (r.assignee() != null && !r.assignee().isBlank()) {
                User u = byName.get(key(r.assignee()));
                if (u == null) problem(problems, rowNo, name, "assignee \"" + r.assignee().trim() + "\" not found - ignored");
                else if (!isDev.computeIfAbsent(u.getId(), id -> u.isEnabled() && access.levelIn(u, p) >= Lookups.DEVELOPER))
                    problem(problems, rowNo, name, "assignee \"" + u.getUsername() + "\" is not a developer in this project - ignored");
                else handler = u;
            }
            Category existing = own.get(k);
            if (existing != null) {
                if (handler != null && (existing.getDefaultHandler() == null || !existing.getDefaultHandler().getId().equals(handler.getId()))) {
                    existing.setDefaultHandler(handler); updated++;
                } else skipped++;
                continue;
            }
            if (inherited.contains(k)) { skipped++; problem(problems, rowNo, name, "already exists in a parent project (inherited)"); continue; }
            Category c = new Category();
            c.setProject(p); c.setName(name); c.setDefaultHandler(handler);
            fresh.add(c); created++;
        }
        categories.saveAll(fresh);
        return new ImportResult(created, updated, skipped, 0, problems);
    }

    // ================================================================== versions
    public ImportResult versions(User me, Long pid, List<BulkVersionRow> rows) {
        Project p = access.projectOrThrow(pid);
        access.require(me, p, Lookups.MANAGER);
        Set<String> have = versions.findByProjectIdOrderByDateOrderDesc(pid).stream().map(v -> key(v.getVersion())).collect(Collectors.toSet());
        List<ProjectVersion> fresh = new ArrayList<>();
        List<RowMessage> problems = new ArrayList<>();
        int skipped = 0, rowNo = 0;
        Instant order = Instant.now();
        for (BulkVersionRow r : rows) {
            rowNo++;
            String name = r.version() == null ? "" : r.version().trim();
            if (name.isEmpty()) { skipped++; continue; }
            if (name.length() > 64) { skipped++; problem(problems, rowNo, name.substring(0, 40) + "…", "name longer than 64 characters"); continue; }
            if (!have.add(key(name))) { skipped++; problem(problems, rowNo, name, "already exists"); continue; }
            ProjectVersion v = new ProjectVersion();
            v.setProject(p); v.setVersion(name);
            if (r.description() != null) v.setDescription(r.description().trim());
            v.setReleased(Boolean.TRUE.equals(r.released()));
            v.setObsolete(Boolean.TRUE.equals(r.obsolete()));
            v.setDateOrder(order = order.plusMillis(1));        // keeps the order of the list
            fresh.add(v);
        }
        versions.saveAll(fresh);
        return new ImportResult(fresh.size(), 0, skipped, 0, problems);
    }

    // ================================================================== members
    /** Adds users to the project with a role, or changes the role of existing members. */
    public ImportResult members(User me, Long pid, BulkMemberRequest req) {
        Project p = access.projectOrThrow(pid);
        int myLevel = access.require(me, p, Lookups.MANAGER);
        boolean create = Boolean.TRUE.equals(req.createMissing());
        String passwordHash = null;
        if (create) {
            access.requireAdmin(me);
            if (req.initialPassword() == null || req.initialPassword().length() < 6)
                throw bad("An initial password of at least 6 characters is required to create users");
            passwordHash = encoder.encode(req.initialPassword());   // one hash for all new users (BCrypt per user would take minutes for hundreds)
        }
        Map<String, User> byName = new HashMap<>();
        Map<String, List<User>> byEmail = new HashMap<>();
        for (User u : users.findAll()) {
            byName.put(key(u.getUsername()), u);
            if (u.getEmail() != null && !u.getEmail().isBlank()) byEmail.computeIfAbsent(key(u.getEmail()), k -> new ArrayList<>()).add(u);
        }
        Map<Long, ProjectUser> current = members.findByProjectId(pid).stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m, (a, b) -> a));

        List<RowMessage> problems = new ArrayList<>();
        Set<Long> done = new HashSet<>();
        int created = 0, updated = 0, skipped = 0, rowNo = 0;
        for (BulkMemberRow r : req.rows()) {
            rowNo++;
            String ident = r.user() == null ? "" : r.user().trim();
            if (ident.isEmpty()) { skipped++; continue; }

            Integer level = r.accessLevel() != null ? r.accessLevel() : req.defaultLevel();
            if (level == null) { skipped++; problem(problems, rowNo, ident, "no role given"); continue; }
            if (!Lookups.ACCESS.containsKey(level) || level <= 0 || level >= Lookups.ADMINISTRATOR) { skipped++; problem(problems, rowNo, ident, "invalid role"); continue; }
            if (level > myLevel) { skipped++; problem(problems, rowNo, ident, "you cannot grant a role higher than your own"); continue; }

            User u = byName.get(key(ident));
            if (u == null) {
                List<User> hits = byEmail.getOrDefault(key(ident), List.of());
                if (hits.size() == 1) u = hits.get(0);
                else if (hits.size() > 1) { skipped++; problem(problems, rowNo, ident, "e-mail belongs to several users - use the username"); continue; }
            }
            if (u == null && create) {
                String email = r.email() == null ? "" : r.email().trim();
                if (ident.contains("@")) { skipped++; problem(problems, rowNo, ident, "unknown user - to create him give a username and an e-mail"); continue; }
                if (email.isEmpty() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) { skipped++; problem(problems, rowNo, ident, "unknown user and no valid e-mail to create him"); continue; }
                if (ident.length() > 191) { skipped++; problem(problems, rowNo, ident, "username too long"); continue; }
                User n = new User();
                n.setUsername(ident); n.setEmail(email); n.setRealname(r.realname() == null ? "" : r.realname().trim());
                n.setPassword(passwordHash); n.setAccessLevel(Lookups.REPORTER);      // global level; the project role is set below
                u = users.save(n);
                byName.put(key(ident), u);
            } else if (u == null) {
                skipped++; problem(problems, rowNo, ident, "user not found"); continue;
            }
            if (!u.isEnabled()) { skipped++; problem(problems, rowNo, ident, "user is disabled"); continue; }
            if (access.isAdmin(u)) { skipped++; problem(problems, rowNo, ident, "administrator - has access to every project anyway"); continue; }
            if (!done.add(u.getId())) { skipped++; problem(problems, rowNo, ident, "listed twice"); continue; }

            ProjectUser pu = current.get(u.getId());
            if (pu == null) {
                pu = new ProjectUser(); pu.setProject(p); pu.setUser(u); pu.setAccessLevel(level);
                members.save(pu); current.put(u.getId(), pu); created++;
            } else if (pu.getAccessLevel() != level) {
                pu.setAccessLevel(level); updated++;
            } else skipped++;
        }
        return new ImportResult(created, updated, skipped, 0, problems);
    }

    public ImportResult removeMembers(User me, Long pid, List<Long> userIds) {
        Project p = access.projectOrThrow(pid);
        access.require(me, p, Lookups.MANAGER);
        int removed = 0;
        for (Long uid : new LinkedHashSet<>(userIds)) {
            Optional<ProjectUser> pu = members.findByProjectIdAndUserId(pid, uid);
            if (pu.isPresent()) { members.delete(pu.get()); removed++; }
        }
        return new ImportResult(0, 0, userIds.size() - removed, removed, List.of());
    }

    // ================================================================== copy the setup of another project
    public CopyResult copyFrom(User me, Long targetId, CopyFromRequest req) {
        Project target = access.projectOrThrow(targetId);
        int myLevel = access.require(me, target, Lookups.MANAGER);
        Project source = access.projectOrThrow(req.sourceProjectId());
        if (source.getId().equals(target.getId())) throw bad("Source and target project are the same");
        int srcLevel = access.require(me, source, Lookups.VIEWER);

        boolean wantMembers = Boolean.TRUE.equals(req.members());
        boolean wantFields = Boolean.TRUE.equals(req.customFields());
        if ((wantMembers || wantFields) && srcLevel < Lookups.MANAGER)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You must be a manager of the source project to copy its members or custom fields");

        List<RowMessage> problems = new ArrayList<>();
        int nMembers = 0, nCats = 0, nVers = 0, nFields = 0;

        if (wantMembers) {
            Set<Long> have = members.findByProjectId(targetId).stream().map(m -> m.getUser().getId()).collect(Collectors.toSet());
            for (ProjectUser m : members.findByProjectId(source.getId())) {
                if (have.contains(m.getUser().getId())) continue;
                if (m.getAccessLevel() > myLevel) { problem(problems, 0, m.getUser().getUsername(), "role higher than yours - not copied"); continue; }
                ProjectUser n = new ProjectUser();
                n.setProject(target); n.setUser(m.getUser()); n.setAccessLevel(m.getAccessLevel());
                members.save(n); nMembers++;
            }
        }
        if (Boolean.TRUE.equals(req.categories())) {
            Set<String> have = new HashSet<>();
            categories.findByProjectIdOrderByName(targetId).forEach(c -> have.add(key(c.getName())));
            for (Project a : access.inheritedAncestors(target))
                categories.findByProjectIdOrderByName(a.getId()).forEach(c -> have.add(key(c.getName())));
            List<Category> fresh = new ArrayList<>();
            for (Category c : categories.findByProjectIdOrderByName(source.getId())) {
                if (!have.add(key(c.getName()))) continue;
                Category n = new Category();
                n.setProject(target); n.setName(c.getName());
                User h = c.getDefaultHandler();
                if (h != null && h.isEnabled() && access.levelIn(h, target) >= Lookups.DEVELOPER) n.setDefaultHandler(h);
                fresh.add(n);
            }
            categories.saveAll(fresh); nCats = fresh.size();
        }
        if (Boolean.TRUE.equals(req.versions())) {
            Set<String> have = versions.findByProjectIdOrderByDateOrderDesc(targetId).stream().map(v -> key(v.getVersion())).collect(Collectors.toSet());
            List<ProjectVersion> fresh = new ArrayList<>();
            for (ProjectVersion v : versions.findByProjectIdOrderByDateOrderDesc(source.getId())) {
                if (!have.add(key(v.getVersion()))) continue;
                ProjectVersion n = new ProjectVersion();
                n.setProject(target); n.setVersion(v.getVersion()); n.setDescription(v.getDescription());
                n.setReleased(v.isReleased()); n.setObsolete(v.isObsolete()); n.setDateOrder(v.getDateOrder());
                fresh.add(n);
            }
            versions.saveAll(fresh); nVers = fresh.size();
        }
        if (wantFields) {
            Set<Long> have = fieldLinks.findByProjectIdOrderBySequenceAscIdAsc(targetId).stream().map(l -> l.getField().getId()).collect(Collectors.toSet());
            for (CustomFieldProject l : fieldLinks.findByProjectIdOrderBySequenceAscIdAsc(source.getId())) {
                if (!have.add(l.getField().getId())) continue;
                CustomFieldProject n = new CustomFieldProject();
                n.setField(l.getField()); n.setProject(target); n.setSequence(l.getSequence());
                fieldLinks.save(n); nFields++;
            }
        }
        return new CopyResult(nCats, nVers, nMembers, nFields, problems);
    }
}
