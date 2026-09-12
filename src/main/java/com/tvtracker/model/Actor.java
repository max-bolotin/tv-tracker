package com.tvtracker.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Actor {
    public String name;
    public String profilePath;
    public String linkUrl;

    public Actor() {}

    public Actor(String name, String profilePath) {
        this(name, profilePath, null);
    }

    public Actor(String name, String profilePath, String linkUrl) {
        this.name = name;
        this.profilePath = profilePath;
        this.linkUrl = linkUrl;
    }
}
