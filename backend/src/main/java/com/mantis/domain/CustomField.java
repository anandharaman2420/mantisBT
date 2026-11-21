package com.mantis.domain;

import jakarta.persistence.*;
import lombok.*;

/** mantis_custom_field_table - definition of an admin-defined field */
@Entity @Table(name = "mantis_custom_field_table")
@Getter @Setter @NoArgsConstructor
public class CustomField {
    /** Mantis CUSTOM_FIELD_TYPE_* constants */
    public static final int STRING = 0, NUMERIC = 1, FLOAT = 2, ENUM = 3, EMAIL = 4, CHECKBOX = 5, LIST = 6,
            MULTILIST = 7, DATE = 8, RADIO = 9, TEXTAREA = 10;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String name;
    private int type = STRING;
    /** options for enum/list/radio/checkbox/multilist, separated by '|' */
    @Column(name = "possible_values", columnDefinition = "text") private String possibleValues = "";
    @Column(name = "default_value", columnDefinition = "text") private String defaultValue = "";
    @Column(name = "valid_regexp", length = 255) private String validRegexp = "";
    /** minimum access level to see / to edit the field */
    @Column(name = "access_level_r") private int accessLevelR = 10;
    @Column(name = "access_level_rw") private int accessLevelRw = 25;
    /** string length limits (numeric/float: value limits); 0 = unlimited */
    @Column(name = "length_min") private int lengthMin = 0;
    @Column(name = "length_max") private int lengthMax = 0;
    @Column(name = "require_report") private boolean requireReport = false;
    @Column(name = "require_update") private boolean requireUpdate = false;
    @Column(name = "display_report") private boolean displayReport = true;
    @Column(name = "display_update") private boolean displayUpdate = true;
    @Column(name = "display_resolved") private boolean displayResolved = false;
    @Column(name = "display_closed") private boolean displayClosed = false;
    @Column(name = "filter_by") private boolean filterBy = true;
}
