package com.pawelniewiadomski.jira.openid.authentication.providers;

import com.atlassian.fugue.Either;
import com.atlassian.fugue.Pair;
import com.atlassian.sal.api.message.I18nResolver;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.pawelniewiadomski.jira.openid.authentication.ReturnToHelper;
import com.pawelniewiadomski.jira.openid.authentication.activeobjects.OpenIdDao;
import com.pawelniewiadomski.jira.openid.authentication.activeobjects.OpenIdProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthBearerClientRequest;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.GitHubTokenResponse;
import org.apache.oltu.oauth2.client.response.OAuthResourceResponse;
import org.apache.oltu.oauth2.common.OAuth;
import org.apache.oltu.oauth2.common.OAuthProviderType;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.apache.oltu.oauth2.common.message.types.ResponseType;
import org.apache.oltu.oauth2.common.utils.JSONUtils;
import org.codehaus.jackson.type.TypeReference;
import org.json.JSONObject;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
public class GitHubProviderType extends AbstractOAuth2ProviderType {
    private final org.codehaus.jackson.map.ObjectMapper mapper = new org.codehaus.jackson.map.ObjectMapper();
    private final ReturnToHelper returnToHelper;

    public GitHubProviderType(I18nResolver i18nResolver, OpenIdDao openIdDao, ReturnToHelper returnToHelper) {
        super(i18nResolver, openIdDao);
        this.returnToHelper = returnToHelper;
    }

    @Nonnull
    @Override
    public String getAuthorizationUrl() {
        return OAuthProviderType.GITHUB.getAuthzEndpoint();
    }

    @Nonnull
    @Override
    public String getCallbackId() {
        return "github";
    }

    @Nonnull
    @Override
    public String getId() {
        return OpenIdProvider.GITHUB_TYPE;
    }

    @Nonnull
    @Override
    public String getName() {
        return i18nResolver.getText("openid.provider.type.github");
    }

    @Override
    public OAuthClientRequest createOAuthRequest(@Nonnull OpenIdProvider provider,
                                                 @Nonnull String state,
                                                 @Nonnull HttpServletRequest request) throws Exception {
        return OAuthClientRequest
                .authorizationLocation(OAuthProviderType.GITHUB.getAuthzEndpoint())
                .setClientId(provider.getClientId())
                .setResponseType(ResponseType.CODE.toString())
                .setState(state)
                .setScope("user:email")
                .setParameter("prompt", "select_account")
                .setRedirectURI(returnToHelper.getReturnTo(provider, request))
                .buildQueryMessage();
    }

    @Override
    public Either<Pair<String, String>, Error> getUsernameAndEmail(@Nonnull String authorizationCode, @Nonnull OpenIdProvider provider, HttpServletRequest request) throws Exception {
        final OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());
        final OAuthClientRequest oAuthRequest = OAuthClientRequest.tokenLocation(OAuthProviderType.GITHUB.getTokenEndpoint())
                .setGrantType(GrantType.AUTHORIZATION_CODE)
                .setClientId(provider.getClientId())
                .setClientSecret(provider.getClientSecret())
                .setRedirectURI(returnToHelper.getReturnTo(provider, request))
                .setCode(authorizationCode)
                .buildQueryMessage();

        final GitHubTokenResponse token = oAuthClient.accessToken(oAuthRequest, GitHubTokenResponse.class);
        final String accessToken = token.getAccessToken();

        final OAuthClientRequest bearerClientRequest = new OAuthBearerClientRequest("https://api.github.com/user")
                .setAccessToken(accessToken)
                .buildQueryMessage();

        final OAuthResourceResponse userInfoResponse = oAuthClient.resource(bearerClientRequest, OAuth.HttpMethod.GET, OAuthResourceResponse.class);

        final Map<String, Object> userInfo = JSONUtils.parseJSON(userInfoResponse.getBody());

        if (!userInfo.containsKey("email") || JSONObject.NULL.equals(userInfo.get("email"))) {
            final OAuthClientRequest emailsClientRequest = new OAuthBearerClientRequest("https://api.github.com/user/emails")
                    .setAccessToken(accessToken)
                    .buildQueryMessage();

            final OAuthResourceResponse emailsResponse = oAuthClient.resource(emailsClientRequest, OAuth.HttpMethod.GET, OAuthResourceResponse.class);
            final List<EmailResponse> emails = mapper.readValue(emailsResponse.getBody(), new TypeReference<List<EmailResponse>>() {});
            final EmailResponse email = Iterables.getFirst(Iterables.concat(Iterables.filter(emails, new Predicate<EmailResponse>() {
                @Override
                public boolean apply(EmailResponse emailResponse) {
                    return emailResponse.isPrimary();
                }
            }), emails), null);

            if (email == null) {
                return Either.right(Error.builder()
                        .errorMessage("Failed to retrieve user's email. Expected 'email' field, got following payload:")
                        .payload(emailsResponse.getBody()).build());
            }

            return Either.left(Pair.pair((String) userInfo.get("login"), email.getEmail()));
        }

        return Either.left(Pair.pair((String) userInfo.get("login"), (String) userInfo.get("email")));
    }

}
