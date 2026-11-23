package com.mantis.service;

import com.mantis.domain.*;
import com.mantis.dto.Dtos.*;

/** Entity -> DTO conversions (must be called inside a transaction: associations are lazy). */
public final class Mapper {
    private Mapper() {}

    public static UserRef ref(User u) {
        return u == null ? null : new UserRef(u.getId(), u.getUsername(), u.getRealname());
    }

    public static UserView user(User u) {
        return new UserView(u.getId(), u.getUsername(), u.getRealname(), u.getEmail(), u.getAccessLevel(),
                u.isEnabled(), u.isProtectedUser(), u.getDateCreated(), u.getLastVisit(), u.isEmailEnabled());
    }

    public static ProjectView project(Project p, int myAccess) {
        return new ProjectView(p.getId(), p.getName(), p.getStatus(), p.isEnabled(), p.getViewState(),
                p.getDescription(), myAccess, p.getParent() == null ? null : p.getParent().getId(), p.isInheritParent());
    }

    public static IssueSummary summary(Issue i) {
        return new IssueSummary(i.getId(), i.getProject().getId(), i.getProject().getName(), i.getSummary(),
                i.getStatus(), i.getResolution(), i.getPriority(), i.getSeverity(), ref(i.getReporter()),
                ref(i.getHandler()), i.getCategory() == null ? null : i.getCategory().getName(),
                i.getDateSubmitted(), i.getLastUpdated(), i.getViewState(), i.isSticky());
    }

    public static NewsView news(News n) {
        return new NewsView(n.getId(), n.getProject() == null ? null : n.getProject().getId(), ref(n.getPoster()),
                n.getHeadline(), n.getBody(), n.getViewState(), n.isAnnouncement(), n.getDatePosted());
    }
}
