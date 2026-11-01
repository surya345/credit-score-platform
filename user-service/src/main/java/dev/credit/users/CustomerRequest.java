package dev.credit.users;

import jakarta.validation.constraints.*;

public record CustomerRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{3,40}") String reference,
    @NotBlank @Size(max = 120) String fullName,
    @NotBlank @Email @Size(max = 200) String email,
    @NotBlank @Pattern(regexp = "INR|SEK|EUR|USD") String currency) {}
