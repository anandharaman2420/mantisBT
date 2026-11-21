package com.mantis.config;

import com.mantis.domain.*;
import com.mantis.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** First-start data (Mantis admin/install.php equivalent): default administrator + a sample project. */
@Component
public class DataSeeder implements CommandLineRunner {
    private final UserRepository users;
    private final ProjectRepository projects;
    private final CategoryRepository categories;
    private final PasswordEncoder encoder;
    private final String adminUser, adminPass, adminEmail;

    public DataSeeder(UserRepository users, ProjectRepository projects, CategoryRepository categories,
                      PasswordEncoder encoder,
                      @Value("${app.seed.admin-username}") String adminUser,
                      @Value("${app.seed.admin-password}") String adminPass,
                      @Value("${app.seed.admin-email}") String adminEmail) {
        this.users = users; this.projects = projects; this.categories = categories; this.encoder = encoder;
        this.adminUser = adminUser; this.adminPass = adminPass; this.adminEmail = adminEmail;
    }

    @Override @Transactional
    public void run(String... args) {
        if (users.count() == 0) {
            User u = new User();
            u.setUsername(adminUser);
            u.setRealname("Administrator");
            u.setEmail(adminEmail);
            u.setPassword(encoder.encode(adminPass));
            u.setAccessLevel(Lookups.ADMINISTRATOR);
            u.setProtectedUser(false);
            users.save(u);
        }
        if (projects.count() == 0) {
            Project p = new Project();
            p.setName("Sample Project");
            p.setDescription("Created automatically on first start");
            projects.save(p);
            Category c = new Category();
            c.setProject(p);
            c.setName("General");
            categories.save(c);
        }
    }
}
