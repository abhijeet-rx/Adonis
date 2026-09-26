package com.adonis.service;

import com.adonis.dto.UserResponse;
import com.adonis.exception.UserNotFoundException;
import com.adonis.model.User;
import com.adonis.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));
        return UserResponse.fromUser(user);
    }
}
