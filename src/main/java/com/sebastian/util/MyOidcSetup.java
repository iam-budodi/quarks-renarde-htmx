package com.sebastian.util;

import com.sebastian.model.User;
import com.sebastian.model.UserStatus;
import io.quarkiverse.renarde.oidc.RenardeOidcHandler;
import io.quarkiverse.renarde.oidc.RenardeOidcSecurity;
import io.quarkiverse.renarde.router.Router;
import io.quarkiverse.renarde.security.RenardeSecurity;
import io.quarkiverse.renarde.security.RenardeUser;
import io.quarkiverse.renarde.security.RenardeUserProvider;
import io.quarkiverse.renarde.util.Flash;
import io.quarkiverse.renarde.util.RedirectException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.UUID;

@ApplicationScoped
public class MyOidcSetup implements RenardeUserProvider, RenardeOidcHandler {

    @Inject
    RenardeOidcSecurity oidcSecurity;

    @Inject
    RenardeSecurity security;

    @Inject
    Flash flash;

    @Override
    public RenardeUser findUser(String tenantId, String id) {
        if (tenantId == null || tenantId.equals("manual")) {
            return User.findByUserName(id);
        } else {
            return User.findByAuthId(tenantId, id);
        }
    }

    /**
     * This will be called on every successful OIDC authentication,
     * be it a first-time user registration, or existing user login
     */
    @Transactional
    @Override
    public void oidcSuccess(String tenantId, String authId) {
        User user = User.findByAuthId(tenantId, authId);
        URI uri;
        if (user == null) {
            // registration
            user = new User();
            user.tenantId = tenantId;
            user.authId = authId;

            user.email = oidcSecurity.getOidcEmail();
            // workaround for Twitter
            if (user.email == null)
                user.email = "twitter@example.com";
            user.firstName = oidcSecurity.getOidcFirstName();
            user.lastName = oidcSecurity.getOidcLastName();
            user.userName = oidcSecurity.getOidcUserName();

            user.status = UserStatus.CONFIRMATION_REQUIRED;
            user.confirmationCode = UUID.randomUUID().toString();
            user.persist();

            // go to registration
            uri = Router.getURI(Login::confirm, user.confirmationCode);
        } else if (!user.registered()) {
            // user exists, but not fully registered yet
            // go to registration
            uri = Router.getURI(Login::confirm, user.confirmationCode);
        } else {
            // regular login
            uri = Router.getURI(Application::index);
        }
        throw new RedirectException(Response.seeOther(uri).build());
    }

    /**
     * This will be called if the existing user has a valid OIDC session,
     * and attempts to login again, so we check if the user exists, and is
     * fully registered.
     */
    @Override
    public void loginWithOidcSession(String tenantId, String authId) {
        RenardeUser user = findUser(tenantId, authId);
        // old cookie, no such user
        if (user == null) {
            flash.flash("message", "Invalid user: " + authId);
            throw new RedirectException(security.makeLogoutResponse());
        }
        // redirect to registration
        URI uri;
        if (!user.registered()) {
            uri = Router.getURI(Login::confirm, ((User) user).confirmationCode);
        } else {
            flash.flash("message", "Already logged in");
            uri = Router.getURI(Application::index);
        }
        throw new RedirectException(Response.seeOther(uri).build());
    }
}