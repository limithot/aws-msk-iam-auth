package com.amazonaws.msk.auth.iam.internals;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.msk.auth.iam.IAMClientCallbackHandler;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.IllegalSaslStateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.text.ParseException;
import java.util.Collections;
import java.util.function.Supplier;

public class IAMSaslClientTest {
    private static final String VALID_HOSTNAME = "b-3.unit-test.abcdef.kafka.us-west-2.amazonaws.com";
    private static final String AWS_MSK_IAM = "AWS_MSK_IAM";
    private static final String ACCESS_KEY_VALUE = "ACCESS_KEY_VALUE";
    private static final String SECRET_KEY_VALUE = "SECRET_KEY_VALUE";
    private static final String ACCESS_KEY_VALUE_TWO = "ACCESS_KEY_VALUE_TWO";
    private static final String SECRET_KEY_VALUE_TWO = "SECRET_KEY_VALUE_TWO";

    private static final BasicAWSCredentials BASIC_AWS_CREDENTIALS = new BasicAWSCredentials(ACCESS_KEY_VALUE, SECRET_KEY_VALUE);

    @Test
    public void testCompleteValidExchange() throws IOException, ParseException {
        SaslClient saslClient = getSuccessfulIAMClient(getIamClientCallbackHandler());
        runValidExchangeForSaslClient(saslClient, ACCESS_KEY_VALUE, SECRET_KEY_VALUE);
    }

    private void runValidExchangeForSaslClient(SaslClient saslClient, String accessKey, String secretKey) {
        assertEquals(AWS_MSK_IAM, saslClient.getMechanismName());
        assertTrue(saslClient.hasInitialResponse());
        SystemPropertyCredentialsUtils.runTestWithSystemPropertyCredentials(() -> {
            try {
                byte[] response = saslClient.evaluateChallenge(new byte[]{});

                SignedPayloadValidatorUtils
                        .validatePayload(response,
                                AuthenticationRequestParams
                                        .create(VALID_HOSTNAME, new BasicAWSCredentials(accessKey, secretKey)));
                assertFalse(saslClient.isComplete());

                saslClient.evaluateChallenge(new byte[]{});
                assertTrue(saslClient.isComplete());
            } catch (Exception e) {
                throw new RuntimeException("Test failed", e);
            }
        }, accessKey, secretKey);
    }

    @Test
    public void testMultipleSaslClients() throws IOException, ParseException {
        IAMClientCallbackHandler cbh = getIamClientCallbackHandler();

        //test the first Sasl client with 1 set of credentials.
        SaslClient saslClient1 = getSuccessfulIAMClient(cbh);
        runValidExchangeForSaslClient(saslClient1, ACCESS_KEY_VALUE, SECRET_KEY_VALUE);

        //test second sasl client with another set of credentials
        SaslClient saslClient2 = getSuccessfulIAMClient(cbh);
        runValidExchangeForSaslClient(saslClient2, ACCESS_KEY_VALUE_TWO, SECRET_KEY_VALUE_TWO);
    }

    private IAMClientCallbackHandler getIamClientCallbackHandler() {
        IAMClientCallbackHandler cbh = new IAMClientCallbackHandler();
        cbh.configure(Collections.EMPTY_MAP, AWS_MSK_IAM, Collections.emptyList());
        return cbh;
    }

    @Test
    public void testNonEmptyChallenge() throws SaslException {
        SaslClient saslClient = getSuccessfulIAMClient(getIamClientCallbackHandler());
        SystemPropertyCredentialsUtils.runTestWithSystemPropertyCredentials(() -> {
                    assertThrows(SaslException.class, () -> saslClient.evaluateChallenge(new byte[]{2, 3}));
                }, ACCESS_KEY_VALUE, SECRET_KEY_VALUE);
        assertFalse(saslClient.isComplete());
    }

    @Test
    public void testFailedCallback() throws SaslException {
        SaslClient saslClient = getFailureIAMClient();
        assertThrows(SaslException.class, () -> saslClient.evaluateChallenge(new byte[]{}));
        assertFalse(saslClient.isComplete());
    }

    @Test
    public void testThrowingCallback() throws SaslException {
        SaslClient saslClient = getThrowingIAMClient();
        assertThrows(SaslException.class, () -> saslClient.evaluateChallenge(new byte[]{}));
        assertFalse(saslClient.isComplete());
    }

    @Test
    public void testNonEmptyServerResponse() throws SaslException {
        SaslClient saslClient = getSuccessfulIAMClient(getIamClientCallbackHandler());
        assertEquals(AWS_MSK_IAM, saslClient.getMechanismName());
        assertTrue(saslClient.hasInitialResponse());
        SystemPropertyCredentialsUtils.runTestWithSystemPropertyCredentials(() -> {
            try {
                byte[] response = saslClient.evaluateChallenge(new byte[]{});
            } catch (SaslException e) {
                throw new RuntimeException("Test failed", e);
            }
            assertFalse(saslClient.isComplete());

            assertThrows(SaslException.class, () -> saslClient.evaluateChallenge(new byte[]{3, 4}));
            assertFalse(saslClient.isComplete());

            assertThrows(IllegalSaslStateException.class, () -> saslClient.evaluateChallenge(new byte[]{}));
        }, ACCESS_KEY_VALUE, SECRET_KEY_VALUE);
    }

    @Test
    public void testFactoryMechanisms() {
        assertArrayEquals(new String[]{AWS_MSK_IAM},
                new IAMSaslClient.IAMSaslClientFactory().getMechanismNames(Collections.emptyMap()));
    }

    @Test
    public void testInvalidMechanism() {

        assertThrows(SaslException.class, () -> new IAMSaslClient.IAMSaslClientFactory()
                .createSaslClient(new String[]{AWS_MSK_IAM + "BAD"}, "AUTH_ID", "PROTOCOL", VALID_HOSTNAME,
                        Collections.emptyMap(),
                        new SuccessfulIAMCallbackHandler(BASIC_AWS_CREDENTIALS)));
    }

    private static class SuccessfulIAMCallbackHandler extends IAMClientCallbackHandler {
        private final BasicAWSCredentials basicAWSCredentials;

        public SuccessfulIAMCallbackHandler(BasicAWSCredentials basicAWSCredentials) {
            this.basicAWSCredentials = basicAWSCredentials;
        }

        @Override
        protected void handleCallback(AWSCredentialsCallback callback) {
            callback.setAwsCredentials(basicAWSCredentials);
        }
    }

    private SaslClient getSuccessfulIAMClient(IAMClientCallbackHandler cbh) throws SaslException {
        return getIAMClient(() -> cbh);
    }

    private SaslClient getFailureIAMClient() throws SaslException {
        return getIAMClient(() -> new IAMClientCallbackHandler() {
            @Override
            protected void handleCallback(AWSCredentialsCallback callback) {
                callback.setLoadingException(new IllegalArgumentException("TEST Exception"));
            }
        });
    }

    private SaslClient getThrowingIAMClient() throws SaslException {
        return getIAMClient(() -> new IAMClientCallbackHandler() {
            @Override
            protected void handleCallback(AWSCredentialsCallback callback) throws IOException {
                throw new IOException("TEST IO Exception");
            }
        });
    }

    private SaslClient getIAMClient(Supplier<IAMClientCallbackHandler> handlerSupplier) throws SaslException {
        return new IAMSaslClient.IAMSaslClientFactory()
                .createSaslClient(new String[]{AWS_MSK_IAM}, "AUTH_ID", "PROTOCOL", VALID_HOSTNAME,
                        Collections.emptyMap(),
                        handlerSupplier.get());
    }

}
