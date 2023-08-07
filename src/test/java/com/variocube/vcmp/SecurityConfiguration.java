package com.variocube.vcmp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;


@Configuration
public class SecurityConfiguration {

    public static final String USERNAME = "clientUser";
    public static final String PASSWORD = "password";

    public static final String ALICE_USERNAME = "alice";
    public static final String ALICE_PASSWORD = "password";

    public static final String BOB_USERNAME = "bob";
    public static final String BOB_PASSWORD = "password";

    @Bean
    public SecurityFilterChain configure(HttpSecurity http) throws Exception {
        return http.httpBasic(basic -> {}).build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
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

        return new InMemoryUserDetailsManager(user, alice, bob);
    }
}
