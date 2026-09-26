package com.adonis.service;

import com.adonis.dto.AuthResponse;
import com.adonis.dto.LoginRequest;
import com.adonis.dto.RegisterRequest;
import com.adonis.dto.UserResponse;
import com.adonis.exception.EmailAlreadyExistsException;
import com.adonis.model.User;
import com.adonis.repository.UserRepository;
import com.adonis.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.normalizedEmail();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException("An account with email " + normalizedEmail + " already exists");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.create(request.name().trim(), normalizedEmail, passwordHash);
        User savedUser = userRepository.save(user);

        String token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getName());
        return new AuthResponse(token, UserResponse.fromUser(savedUser));
    }

    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.normalizedEmail();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getName());
        return new AuthResponse(token, UserResponse.fromUser(user));
    }
}
