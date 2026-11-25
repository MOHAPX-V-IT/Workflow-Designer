package app.process;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {
    @Bean
    SecurityFilterChain browser(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(policy -> policy
                .requestMatchers("/assets/**", "/login", "/error").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(login -> login.loginPage("/login").usernameParameter("email").defaultSuccessUrl("/", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self';"
                    + " connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")))
            .build();
    }
}
