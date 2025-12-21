package com.newcodes7.small_town.auth.oauth;

import java.util.Map;

public class GitHubOAuth2UserInfo extends OAuth2UserInfo {

    public GitHubOAuth2UserInfo(Map<String, Object> attributes) {
        super(attributes);
    }

    @Override
    public String getId() {
        return String.valueOf(attributes.get("id"));
    }

    @Override
    public String getName() {
        String name = (String) attributes.get("name");
        return name != null ? name : (String) attributes.get("login");
    }

    @Override
    public String getEmail() {
        return (String) attributes.get("email");
    }

    @Override
    public String getImageUrl() {
        return (String) attributes.get("avatar_url");
    }

    // GitHub 추가 정보 메서드들
    public String getLogin() {
        return (String) attributes.get("login");
    }

    public String getBio() {
        return (String) attributes.get("bio");
    }

    public String getBlog() {
        return (String) attributes.get("blog");
    }

    public String getCompany() {
        return (String) attributes.get("company");
    }

    public String getLocation() {
        return (String) attributes.get("location");
    }

    public String getHtmlUrl() {
        return (String) attributes.get("html_url");
    }

    public Integer getPublicRepos() {
        Object value = attributes.get("public_repos");
        return value != null ? ((Number) value).intValue() : null;
    }

    public Integer getFollowers() {
        Object value = attributes.get("followers");
        return value != null ? ((Number) value).intValue() : null;
    }

    public Integer getFollowing() {
        Object value = attributes.get("following");
        return value != null ? ((Number) value).intValue() : null;
    }

    public String getTwitterUsername() {
        return (String) attributes.get("twitter_username");
    }

    public Boolean getHireable() {
        return (Boolean) attributes.get("hireable");
    }
}