package com.robothy.s3.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import com.robothy.s3.rest.LocalS3Environment;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The options of the executable jar, which are the values of the environment variables of
 * {@linkplain LocalS3Environment}.
 */
class CommandLineTest {

    /**
     * Resolves no variable at all, i.e. an empty environment.
     */
    private static final UnaryOperator<String> NO_ENVIRONMENT = name -> null;

    @Test
    void parsesTheValuesOfVariables() {
        Map<String, String> values = CommandLine.parse(new String[] {"--port", "29292", "--mode", "PERSISTENCE",
                "--data-path", "/var/lib/local-s3", "--access-key", "local", "--secret-key", "local-secret"}).values();

        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_PORT, "29292",
                LocalS3Environment.LOCAL_S3_MODE, "PERSISTENCE",
                LocalS3Environment.LOCAL_S3_DATA_PATH, "/var/lib/local-s3",
                LocalS3Environment.AWS_ACCESS_KEY_ID, "local",
                LocalS3Environment.AWS_SECRET_ACCESS_KEY, "local-secret"), values);
    }

    /**
     * A value is taken from the argument that follows the option, or from the one it carries after an {@code =},
     * which a value that looks like an option of its own needs.
     */
    @Test
    void parsesAValueThatTheOptionCarriesItself() {
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_PORT, "29292", LocalS3Environment.LOCAL_S3_HOST, "0.0.0.0"),
                CommandLine.parse(new String[] {"--port=29292", "--host=0.0.0.0"}).values());
    }

    /**
     * An option is the most explicit of the three, so it wins over the variable of the same setting; a variable
     * the command line doesn't set is read from the environment as before.
     */
    @Test
    void winsOverTheEnvironment() {
        UnaryOperator<String> environment = Map.of(LocalS3Environment.LOCAL_S3_PORT, "29090",
                LocalS3Environment.LOCAL_S3_MODE, "PERSISTENCE")::get;

        UnaryOperator<String> variables = CommandLine.parse(new String[] {"--port", "29292"}).variables(environment);

        assertEquals("29292", variables.apply(LocalS3Environment.LOCAL_S3_PORT));
        assertEquals("PERSISTENCE", variables.apply(LocalS3Environment.LOCAL_S3_MODE));
        assertNull(variables.apply(LocalS3Environment.LOCAL_S3_HOST));
    }

    /**
     * The service is configured from the options, through the very same code that applies the variables.
     */
    @Test
    void configuresTheService() {
        CommandLine commandLine = CommandLine.parse(new String[] {"--port", "29292", "--host", "0.0.0.0",
                "--virtual-host-domains", "s3,s3.local"});

        try (LocalS3 localS3 = App.configure(commandLine.variables(NO_ENVIRONMENT)).build()) {
            assertEquals(29292, localS3.getPort());
            assertEquals("0.0.0.0", localS3.getBindHost());
            assertEquals(List.of("s3", "s3.local"), localS3.getVirtualHostDomains());
            assertEquals(LocalS3Mode.IN_MEMORY, localS3.getMode());
        }
    }

    /**
     * Buckets are the one option that is usually written once per value, so repeating it adds to the list
     * rather than replacing it.
     */
    @Test
    void addsUpTheBucketsOfARepeatedOption() {
        assertEquals(Map.of(LocalS3Environment.AWS_BUCKETS, "alpha,beta,gamma"),
                CommandLine.parse(new String[] {"--bucket", "alpha", "--buckets", "beta,gamma"}).values());
    }

    /**
     * Any other option that is given twice is rejected: one of the two values would be dropped silently, which
     * is what the command line is read instead of the environment to avoid.
     */
    @Test
    void rejectsAnOptionThatIsGivenTwice() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"--port", "29292", "--port", "29090"}));
        assertTrue(e.getMessage().contains("--port"), e.getMessage());
    }

    /**
     * A flag is {@code true} on its own, and takes the boolean that follows it, but never an argument that
     * could be an option of its own.
     */
    @Test
    void parsesFlags() {
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_TLS_REQUIRED, "true", LocalS3Environment.LOCAL_S3_PORT, "29292"),
                CommandLine.parse(new String[] {"--tls-required", "--port", "29292"}).values());
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_WEBSITE, "false"),
                CommandLine.parse(new String[] {"--website", "false"}).values());
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_WEBSITE, "false"),
                CommandLine.parse(new String[] {"--website=false"}).values());
    }

    /**
     * A flag that is given something other than a boolean is rejected, rather than read as {@code false} by
     * {@code Boolean.parseBoolean}, which is what {@code --website yes} would otherwise turn into.
     */
    @Test
    void rejectsAFlagThatIsNotABoolean() {
        for (String[] args : List.of(new String[] {"--website", "yes"}, new String[] {"--website=yes"})) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(args));
            assertTrue(e.getMessage().contains("--website"), e.getMessage());
        }
    }

    /**
     * The hosts of a generated certificate may be left out, which issues it for the default hosts.
     */
    @Test
    void parsesAnOptionalValue() {
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "true"),
                CommandLine.parse(new String[] {"--tls-self-signed"}).values());
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "localhost,s3"),
                CommandLine.parse(new String[] {"--tls-self-signed", "localhost,s3"}).values());
    }

    @Test
    void rejectsAnOptionWithNoValue() {
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {"--port="}));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {"--port", " "}));
    }

    /**
     * The option that follows one whose value was left out is not read as that value: a data path of
     * {@code --port} would be created, and the port it was meant for served by nothing.
     */
    @Test
    void rejectsAnOptionAsTheValueOfTheOneBeforeIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"--data-path", "--port", "29292"}));
        assertTrue(e.getMessage().contains("--data-path"), e.getMessage());
        // A value that really does begin with a dash is written with an `=`.
        assertEquals(Map.of(LocalS3Environment.LOCAL_S3_DATA_PATH, "--odd"),
                CommandLine.parse(new String[] {"--data-path=--odd"}).values());
    }

    /**
     * An argument that isn't an option of this service is rejected, rather than ignored: a jar that is started
     * with a misspelled option would otherwise serve something other than what was asked for, and say nothing.
     */
    @Test
    void rejectsAnArgumentThatIsNotAnOption() {
        IllegalArgumentException misspelled = assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"--ports", "29292"}));
        assertTrue(misspelled.getMessage().contains("Did you mean --port?"), misspelled.getMessage());

        IllegalArgumentException positional = assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"start"}));
        assertTrue(positional.getMessage().contains("start"), positional.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--help", "-h"})
    void answersTheHelp(String option) {
        assertTrue(CommandLine.parse(new String[] {"--port", "29292", option}).helpRequested());
        assertFalse(CommandLine.parse(new String[] {"--port", "29292"}).helpRequested());
    }

    /**
     * The help is generated from the options themselves, so that it can't fall behind them; it names the
     * variable of each one, which is what a {@code docker run -e ...} of the same setting is written with.
     */
    @Test
    void describesEveryOption() {
        String help = CommandLine.help();

        for (String variable : variablesOfTheOptions()) {
            assertTrue(help.contains(variable), variable + " is missing from the help:\n" + help);
        }
        assertTrue(help.contains("--help"), help);
    }

    /**
     * There is one option per variable of {@linkplain LocalS3Environment} and no more, so that a variable that
     * is added, renamed or dropped can neither be left without an option nor leave one that configures nothing.
     */
    @Test
    void standsForEveryVariableOfTheEnvironment() {
        List<String> declared = declaredVariables();
        List<String> options = variablesOfTheOptions();

        for (String variable : options) {
            assertTrue(declared.contains(variable), variable + " is not a variable of LocalS3Environment.");
        }
        for (String variable : declared) {
            assertTrue(options.contains(variable), variable + " has no option; add one to CommandLine.");
        }
    }

    /**
     * The variables that the options of the help stand for, i.e. the names the help names in brackets.
     */
    private static List<String> variablesOfTheOptions() {
        return CommandLine.help().lines()
                .filter(line -> line.endsWith("]"))
                .map(line -> line.substring(line.lastIndexOf('[') + 1, line.length() - 1))
                .toList();
    }

    private static List<String> declaredVariables() {
        return List.of(LocalS3Environment.class.getDeclaredFields()).stream()
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(CommandLineTest::value)
                .toList();
    }

    private static String value(Field field) {
        try {
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

}
