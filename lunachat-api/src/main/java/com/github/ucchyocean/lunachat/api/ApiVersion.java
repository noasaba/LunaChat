package com.github.ucchyocean.lunachat.api;

/** Semantic version of the public integration contract. */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {
    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) throw new IllegalArgumentException("negative API version");
    }

    public static ApiVersion parse(String value) {
        String[] parts = ApiConstraints.text(value, "version", 32).split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("API version must be major.minor.patch");
        return new ApiVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    @Override public int compareTo(ApiVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
