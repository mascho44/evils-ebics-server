package de.ostms.evils.ebics.admin;
public record CreateAdminUserRequest(String username, String displayName, String password, String role) { }
