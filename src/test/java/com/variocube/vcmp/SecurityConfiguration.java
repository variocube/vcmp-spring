package com.variocube.vcmp;

/*
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;


@Configuration
@EnableWebSecurity
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {

    public static final String USERNAME = "clientUser";
    public static final String PASSWORD = "password";

    public static final String ALICE_USERNAME = "alice";
    public static final String ALICE_PASSWORD = "password";

    public static final String BOB_USERNAME = "bob";
    public static final String BOB_PASSWORD = "password";


    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.httpBasic().and()
                .authorizeRequests().anyRequest().permitAll();
    }

    @Bean
    @Override
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
 */

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
               .httpBasic(Customizer.withDefaults()).authorizeRequests().anyRequest().authenticated();
        return http.build();


    }
}
