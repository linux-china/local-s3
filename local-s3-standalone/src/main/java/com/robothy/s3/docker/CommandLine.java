package com.robothy.s3.docker;

import com.robothy.s3.rest.LocalS3Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The options of the executable jar, one per environment variable of {@linkplain LocalS3Environment}, parsed into
 * the values of those variables: {@code --port 29292} is the value of {@code LOCAL_S3_PORT}, so a command line and
 * an environment configure the service through the very same code, and neither the parser nor the help below knows
 * what a port or a mode means.
 *
 * <p>An option wins over the variable it stands for, which in turn wins over the system property of that name:
 * a command line is the most explicit of the three, and the one a reader of the command sees.
 *
 * <p>The variables that are read elsewhere than by {@linkplain LocalS3Environment}, i.e. the cache limits of
 * {@code LOCAL_S3_OBJECT_METADATA_CACHE_MAX_ENTRIES} and {@code LOCAL_S3_INITIAL_DATA_CACHE_MAX_*}, have no option:
 * they are read when a cache is created rather than applied to this service, so an option of them would be read
 * by nothing.
 */
final class CommandLine {

    /**
     * How many values an option takes.
     */
    private enum Arity {
        /**
         * A value is required, e.g. {@code --port 29292}.
         */
        VALUE,
        /**
         * {@code true} or {@code false}, which the bare option, e.g. {@code --tls-required}, sets to {@code true}.
         */
        FLAG,
        /**
         * A value that may be left out, e.g. {@code --tls-self-signed} alone or {@code --tls-self-signed s3,localhost};
         * left out, it is {@code true}.
         */
        OPTIONAL_VALUE
    }

    /**
     * An option, and the environment variable it is the value of.
     *
     * @param names the option itself, first, followed by the aliases it is also accepted as.
     * @param variable the name of the variable that {@linkplain LocalS3Environment} applies this value as.
     * @param arity how many values the option takes.
     * @param argument the placeholder of the value in the help, e.g. {@code <port>}; {@code null} for a flag.
     * @param description one line of help.
     */
    private record Option(List<String> names, String variable, Arity arity, String argument, String description) {

        String name() {
            return names.getFirst();
        }

        /**
         * The option as the help spells it, e.g. {@code --tls-self-signed [<hosts>]}.
         */
        String usage() {
            String spelling = String.join(", ", names);
            return switch (arity) {
                case VALUE -> spelling + " " + argument;
                case FLAG -> spelling + " [true|false]";
                case OPTIONAL_VALUE -> spelling + " [" + argument + "]";
            };
        }
    }

    private static final List<Option> OPTIONS = List.of(
            option("--port", LocalS3Environment.LOCAL_S3_PORT, "<port>",
                    "Port to listen on. Default 29090."),
            option("--host", LocalS3Environment.LOCAL_S3_HOST, "<host>",
                    "Address to bind. Default 127.0.0.1; 0.0.0.0 serves every interface."),
            option("--mode", LocalS3Environment.LOCAL_S3_MODE, "<mode>",
                    "IN_MEMORY, the default, or PERSISTENCE, which keeps the data in the data path."),
            option("--data-path", LocalS3Environment.LOCAL_S3_DATA_PATH, "<dir>",
                    "Data directory of a PERSISTENCE service; the initial data of an IN_MEMORY one."),
            option("--persistence-policy", LocalS3Environment.LOCAL_S3_PERSISTENCE_POLICY, "<policy>",
                    "PERSISTENCE mode: DURABLE, the default, commits every change, so a killed process loses"
                            + " nothing (no fsync); FAST commits in the background, at most a second later."),
            option("--in-memory-max-bytes", LocalS3Environment.LOCAL_S3_IN_MEMORY_MAX_BYTES, "<size>",
                    "IN_MEMORY mode: the max heap the stored content takes, e.g. 512m. Default half the max heap."),
            option("--buckets", List.of("--bucket"), LocalS3Environment.AWS_BUCKETS, "<names>",
                    "Comma-separated buckets to create on startup; repeat the option to add more."),
            option("--access-key", List.of("--access-key-id"), LocalS3Environment.LOCAL_S3_ACCESS_KEY_ID, "<key>",
                    "Require requests signed with this access key. Set together with --secret-key."),
            option("--secret-key", List.of("--secret-access-key"), LocalS3Environment.LOCAL_S3_SECRET_ACCESS_KEY,
                    "<secret>", "The secret access key of --access-key."),
            option("--virtual-host-domains", LocalS3Environment.LOCAL_S3_VIRTUAL_HOST_DOMAINS, "<domains>",
                    "Comma-separated base domains of virtual-hosted-style requests, e.g. s3,s3.local."),
            flag("--virtual-threads", LocalS3Environment.LOCAL_S3_VIRTUAL_THREADS,
                    "Handle every request on a virtual thread of its own. Default true."),
            flag("--composite-multipart-etags", LocalS3Environment.LOCAL_S3_COMPOSITE_MULTIPART_ETAGS,
                    "Give a completed multipart upload the entity tag of Amazon S3. Default true."),
            option("--tls-cert", LocalS3Environment.LOCAL_S3_TLS_CERT, "<pem>",
                    "Serve HTTPS with this certificate chain: a PEM file, or the PEM content itself."),
            option("--tls-key", LocalS3Environment.LOCAL_S3_TLS_KEY, "<pem>",
                    "The private key of --tls-cert, unencrypted PKCS#8: a PEM file, or the PEM content itself."),
            optionalValue("--tls-self-signed", LocalS3Environment.LOCAL_S3_TLS_SELF_SIGNED, "<hosts>",
                    "Serve HTTPS with a certificate generated on startup, for localhost, 127.0.0.1 and ::1,"
                            + " or for the comma-separated hosts given."),
            flag("--tls-required", LocalS3Environment.LOCAL_S3_TLS_REQUIRED,
                    "Serve HTTPS alone, instead of HTTP and HTTPS on the same port. Default false."),
            flag("--iceberg-catalog", LocalS3Environment.LOCAL_S3_ICEBERG_CATALOG,
                    "Serve an Iceberg REST catalog under /iceberg/v1. Default false."),
            option("--iceberg-warehouse", LocalS3Environment.LOCAL_S3_ICEBERG_WAREHOUSE, "<uri>",
                    "The warehouse of the Iceberg catalog, e.g. s3://warehouse/, which also turns the catalog on."),
            flag("--website", LocalS3Environment.LOCAL_S3_WEBSITE,
                    "Serve the public buckets as static websites to unsigned requests. Default true."),
            flag("--website-all-buckets", LocalS3Environment.LOCAL_S3_WEBSITE_ALL_BUCKETS,
                    "Serve every bucket as a static website, the private ones included. Default false."),
            option("--website-index-document", LocalS3Environment.LOCAL_S3_WEBSITE_INDEX_DOCUMENT, "<name>",
                    "Index document of the buckets with no website configuration. Default index.html."),
            option("--website-error-document", LocalS3Environment.LOCAL_S3_WEBSITE_ERROR_DOCUMENT, "<name>",
                    "Error document of the buckets with no website configuration, e.g. error.html."),
            option("--cors-allowed-origins", LocalS3Environment.LOCAL_S3_CORS_ALLOWED_ORIGINS, "<origins>",
                    "Comma-separated origins, or *, that a default CORS rule allows on the buckets without a CORS"
                            + " configuration of their own. Default none."),
            option("--cors-allowed-methods", LocalS3Environment.LOCAL_S3_CORS_ALLOWED_METHODS, "<methods>",
                    "Comma-separated methods of the default CORS rule. Default GET,PUT,POST,DELETE,HEAD."),
            option("--cors-allowed-headers", LocalS3Environment.LOCAL_S3_CORS_ALLOWED_HEADERS, "<headers>",
                    "Comma-separated request headers of the default CORS rule. Default *."),
            option("--cors-expose-headers", LocalS3Environment.LOCAL_S3_CORS_EXPOSE_HEADERS, "<headers>",
                    "Comma-separated response headers the default CORS rule exposes. Default ETag, x-amz-*, ..."),
            option("--cors-max-age-seconds", LocalS3Environment.LOCAL_S3_CORS_MAX_AGE_SECONDS, "<seconds>",
                    "Seconds a browser may cache a preflight response of the default CORS rule."));

    /**
     * The width the help is wrapped to, which leaves an 80 column terminal wrapping the longest lines alone
     * more often than not, and a 120 column one wrapping none.
     */
    private static final int MAX_WIDTH = 116;

    private static final List<String> HELP_OPTIONS = List.of("--help", "-h");

    /**
     * The variable that the values of a repeated option are appended to, comma-separated, rather than rejected as
     * a duplicate: {@code --bucket a --bucket b} creates both buckets, which is how one is usually written.
     */
    private static final String APPENDED_VARIABLE = LocalS3Environment.AWS_BUCKETS;

    /**
     * The variables the command line sets, by name, in the order they were given.
     */
    private final Map<String, String> values;

    private final boolean helpRequested;

    private CommandLine(Map<String, String> values, boolean helpRequested) {
        this.values = values;
        this.helpRequested = helpRequested;
    }

    /**
     * Parse a command line into the variables it sets.
     *
     * @param args the arguments of {@code main}.
     * @return the parsed command line.
     * @throws IllegalArgumentException if an argument is not an option of this service, or has no value.
     */
    static CommandLine parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (HELP_OPTIONS.contains(argument)) {
                return new CommandLine(Map.of(), true);
            }
            if (!argument.startsWith("-")) {
                throw new IllegalArgumentException("\"" + argument + "\" is not an option; this service takes"
                        + " options alone, e.g. --port 29090.");
            }
            String name = argument;
            String value = null;
            int equals = argument.indexOf('=');
            if (equals >= 0) {
                name = argument.substring(0, equals);
                value = argument.substring(equals + 1);
            }
            Option option = option(name);
            if (value == null) {
                String next = i + 1 < args.length ? args[i + 1] : null;
                value = switch (option.arity()) {
                    // An option that follows is never the value: `--data-path --port 29090` would otherwise
                    // store the data in a directory named "--port" and serve the default port, quietly. A value
                    // that really does begin with a dash is written as --data-path=--odd.
                    case VALUE -> {
                        if (next == null || isOption(next)) {
                            throw new IllegalArgumentException(name + " requires a value, e.g. " + option.usage() + ".");
                        }
                        i++;
                        yield next;
                    }
                    // A value of its own is taken only when it can't be an option of its own, so that a bare flag
                    // before another option, e.g. `--tls-required --port 29090`, doesn't swallow it. Anything else
                    // that isn't an option, e.g. the `yes` of `--website yes`, was meant as the value of the flag
                    // and is rejected as one, rather than read as an argument of its own.
                    case FLAG -> {
                        if (next == null || next.startsWith("-")) {
                            yield "true";
                        }
                        requireBoolean(option, next);
                        i++;
                        yield next;
                    }
                    case OPTIONAL_VALUE -> next != null && !next.startsWith("-") ? args[++i] : "true";
                };
            }
            apply(values, option, value);
        }
        return new CommandLine(values, false);
    }

    /**
     * Whether the help was asked for, which is answered with {@linkplain #help()} instead of a service.
     */
    boolean helpRequested() {
        return helpRequested;
    }

    /**
     * Resolves the variables of {@linkplain LocalS3Environment} from this command line, falling back to the
     * environment for the ones it doesn't set.
     *
     * @param environment resolves a variable that the command line doesn't set, e.g. from the environment of the
     *     process; may resolve to {@code null}.
     * @return resolves a variable by name.
     */
    UnaryOperator<String> variables(UnaryOperator<String> environment) {
        return name -> values.containsKey(name) ? values.get(name) : environment.apply(name);
    }

    /**
     * The variables this command line sets, by name.
     */
    Map<String, String> values() {
        return Map.copyOf(values);
    }

    /**
     * The help of {@code --help}, listing every option beside the variable it stands for, so that a command line
     * and a {@code docker run -e ...} are written from the same list.
     *
     * @return the help, without a trailing newline.
     */
    static String help() {
        List<String> lines = new ArrayList<>(List.of(
                "Usage: java -jar s3.jar [OPTION]...",
                "",
                "Runs LocalS3, an Amazon S3 implementation for tests and local development; it listens on",
                "http://127.0.0.1:29090 and holds its data in memory unless the options below say otherwise.",
                "",
                "Each option is the value of the environment variable named beside it, and wins over that variable,",
                "which in turn wins over the system property of the same name.",
                "",
                "Options:"));
        int width = OPTIONS.stream().mapToInt(option -> option.usage().length()).max().orElse(0);
        for (Option option : OPTIONS) {
            lines.addAll(describe(option.usage(), option.description() + " [" + option.variable() + "]", width));
        }
        lines.addAll(describe(String.join(", ", HELP_OPTIONS), "Print this help and exit.", width));
        lines.addAll(List.of(
                "",
                "Examples:",
                "  java -jar s3.jar --port 29292 --bucket my-bucket",
                "  java -jar s3.jar --mode PERSISTENCE --data-path ~/local-s3",
                "  java -jar s3.jar --access-key local --secret-key local-secret"));
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * One option of the help, its description wrapped under the first line.
     */
    private static List<String> describe(String usage, String description, int width) {
        List<String> lines = new ArrayList<>();
        String indent = "  " + " ".repeat(width) + "  ";
        StringBuilder line = new StringBuilder("  ").append(usage)
                .append(" ".repeat(Math.max(0, width - usage.length()))).append("  ");
        for (String word : description.split(" ")) {
            if (line.length() + word.length() > MAX_WIDTH && line.length() > indent.length()) {
                lines.add(line.toString().stripTrailing());
                line = new StringBuilder(indent);
            }
            line.append(word).append(' ');
        }
        lines.add(line.toString().stripTrailing());
        return lines;
    }

    /**
     * Record the value of an option, rejecting a second one rather than letting it replace the first silently.
     */
    private static void apply(Map<String, String> values, Option option, String value) {
        if (option.arity() == Arity.FLAG) {
            requireBoolean(option, value);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(option.name() + " requires a value, e.g. " + option.usage() + ".");
        }
        String previous = values.get(option.variable());
        if (previous != null) {
            if (!APPENDED_VARIABLE.equals(option.variable())) {
                throw new IllegalArgumentException(option.name() + " is given more than once, as \"" + previous
                        + "\" and \"" + value + "\".");
            }
            value = previous + "," + value;
        }
        values.put(option.variable(), value);
    }

    /**
     * Whether an argument names an option of this service, i.e. one that was meant as an option rather than as
     * the value of the option before it.
     */
    private static boolean isOption(String argument) {
        String name = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
        return HELP_OPTIONS.contains(name)
                || OPTIONS.stream().anyMatch(option -> option.names().contains(name));
    }

    /**
     * Reject the value of a flag that is neither {@code true} nor {@code false}, which
     * {@code Boolean.parseBoolean} would read as {@code false} without a word.
     */
    private static void requireBoolean(Option option, String value) {
        if (!isBoolean(value)) {
            throw new IllegalArgumentException("\"" + value + "\" is not a valid " + option.name()
                    + "; use true or false, or the option alone for true.");
        }
    }

    private static boolean isBoolean(String value) {
        return value != null && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value));
    }

    /**
     * The option of a name, e.g. {@code --port}.
     *
     * @throws IllegalArgumentException if no option is named that; the message names the closest one, if any.
     */
    private static Option option(String name) {
        return OPTIONS.stream()
                .filter(option -> option.names().contains(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("\"" + name + "\" is not an option of this service."
                        + suggestion(name)));
    }

    /**
     * The option that a mistyped one was probably meant to be, e.g. {@code --ports} for {@code --port}, as a
     * sentence to append to the message; empty when nothing is close enough.
     */
    private static String suggestion(String name) {
        String typed = name.toLowerCase(Locale.ROOT).replace("-", "");
        Optional<String> closest = OPTIONS.stream()
                .flatMap(option -> option.names().stream())
                .filter(candidate -> {
                    String spelling = candidate.toLowerCase(Locale.ROOT).replace("-", "");
                    return spelling.startsWith(typed) || typed.startsWith(spelling);
                })
                .findFirst();
        return closest.map(candidate -> " Did you mean " + candidate + "?").orElse("");
    }

    private static Option option(String name, String variable, String argument, String description) {
        return option(name, List.of(), variable, argument, description);
    }

    private static Option option(String name, List<String> aliases, String variable, String argument,
                                 String description) {
        List<String> names = new ArrayList<>();
        names.add(name);
        names.addAll(aliases);
        return new Option(List.copyOf(names), variable, Arity.VALUE, argument, description);
    }

    private static Option flag(String name, String variable, String description) {
        return new Option(List.of(name), variable, Arity.FLAG, null, description);
    }

    private static Option optionalValue(String name, String variable, String argument, String description) {
        return new Option(List.of(name), variable, Arity.OPTIONAL_VALUE, argument, description);
    }

}
