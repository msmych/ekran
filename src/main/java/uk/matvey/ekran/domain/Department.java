package uk.matvey.ekran.domain;

import java.util.Optional;

public enum Department {

    DIRECTING("directing", "Directing"),
    ACTING("acting", "Acting"),
    WRITING("writing", "Writing"),
    OTHER(null, "Other");

    private final String pathKey;
    private final String displayName;

    Department(String pathKey, String displayName) {
        this.pathKey = pathKey;
        this.displayName = displayName;
    }

    public static Department fromTmdb(String tmdbDepartment) {
        return switch (tmdbDepartment == null ? "" : tmdbDepartment) {
            case "Directing" -> DIRECTING;
            case "Acting" -> ACTING;
            case "Writing" -> WRITING;
            default -> OTHER;
        };
    }

    public static Optional<Department> fromPathKey(String key) {
        for (var department : values()) {
            if (department.pathKey != null && department.pathKey.equalsIgnoreCase(key)) {
                return Optional.of(department);
            }
        }
        return Optional.empty();
    }

    public String pathKey() {
        return pathKey;
    }

    public String displayName() {
        return displayName;
    }
}