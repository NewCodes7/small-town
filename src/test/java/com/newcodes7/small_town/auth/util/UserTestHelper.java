package com.newcodes7.small_town.auth.util;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;

public class UserTestHelper {

    public static User createTestUser(String email, String password, String nickname, Role role, Provider provider) {
        return new User(nickname, email, password, role, provider, null, null);
    }
}
