package com.variocube.vcmp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;




@Service("userService")
public class UserServiceImpl implements UserDetailsService {
    public static final String USERNAME = "clientUser";
    public static final String PASSWORD = "password";

    public static final String ALICE_USERNAME = "alice";
    public static final String ALICE_PASSWORD = "password";

    public static final String BOB_USERNAME = "bob";
    public static final String BOB_PASSWORD = "password";

    static List<UserDetails> list = new ArrayList<>();

    static {
        UserDetails user =
                User.withDefaultPasswordEncoder()
                        .username(USERNAME)
                        .password(PASSWORD)
                        .roles("USER")
                        .build();

        UserDetails alice =
                User.withDefaultPasswordEncoder()
                        .username(ALICE_USERNAME)
                        .password(ALICE_PASSWORD)
                        .roles("USER")
                        .build();

        UserDetails bob =
                User.withDefaultPasswordEncoder()
                        .username(BOB_USERNAME)
                        .password(BOB_PASSWORD)
                        .roles("USER")
                        .build();

        list.add(user);
        list.add(alice);
        list.add(bob);
    }


    @Override
    public UserDetails loadUserByUsername(String name) {
        UserDetails user = list.stream().filter(u -> u.getUsername().equals(name)).findFirst().get();
        return (user);
    }
}