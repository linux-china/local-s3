package com.robothy.s3.rest.handler;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * The condition of a route on the query parameters of a request, declared rather than coded, so that
 * {@linkplain LocalS3Router#verifyRoutes()} can tell which requests the route matches: the parameters that must be
 * present, the ones that must be absent, and the values that parameters must have.
 *
 * <p>A condition is immutable; {@linkplain #andHas}, {@linkplain #andHasNot} and {@linkplain #andEqualTo} return a new
 * one.
 */
final class ParamCondition implements Function<Map<CharSequence, List<String>>, Boolean> {

  private final Set<String> present;

  private final Set<String> absent;

  private final Map<String, String> values;

  private ParamCondition(Set<String> present, Set<String> absent, Map<String, String> values) {
    this.present = Collections.unmodifiableSet(present);
    this.absent = Collections.unmodifiableSet(absent);
    this.values = Collections.unmodifiableMap(values);
  }

  /**
   * A condition that the request has all the given parameters, with any values, e.g. {@code ?acl}.
   *
   * @param names the names of the parameters.
   * @return the condition.
   */
  static ParamCondition has(String... names) {
    return new ParamCondition(Set.of(), Set.of(), Map.of()).andHas(names);
  }

  /**
   * A condition that the request has a parameter with the given value, e.g. {@code ?list-type=2}.
   *
   * @param name the name of the parameter.
   * @param value the value of the parameter.
   * @return the condition.
   */
  static ParamCondition equalTo(String name, String value) {
    return new ParamCondition(Set.of(), Set.of(), Map.of()).andEqualTo(name, value);
  }

  /**
   * This condition, and that the request has the given parameters.
   *
   * @param names the names of the parameters.
   * @return a new condition.
   */
  ParamCondition andHas(String... names) {
    Set<String> newPresent = new LinkedHashSet<>(present);
    newPresent.addAll(Arrays.asList(names));
    return checked(newPresent, new LinkedHashSet<>(absent), new LinkedHashMap<>(values));
  }

  /**
   * This condition, and that the request doesn't have the given parameters, e.g. the {@code id} that tells
   * {@code GetBucketAnalyticsConfiguration} from {@code ListBucketAnalyticsConfigurations}.
   *
   * @param names the names of the parameters.
   * @return a new condition.
   */
  ParamCondition andHasNot(String... names) {
    Set<String> newAbsent = new LinkedHashSet<>(absent);
    newAbsent.addAll(Arrays.asList(names));
    return checked(new LinkedHashSet<>(present), newAbsent, new LinkedHashMap<>(values));
  }

  /**
   * This condition, and that the request has a parameter with the given value.
   *
   * @param name the name of the parameter.
   * @param value the value of the parameter.
   * @return a new condition.
   */
  ParamCondition andEqualTo(String name, String value) {
    Map<String, String> newValues = new LinkedHashMap<>(values);
    newValues.put(Objects.requireNonNull(name), Objects.requireNonNull(value));
    return checked(new LinkedHashSet<>(present), new LinkedHashSet<>(absent), newValues);
  }

  private static ParamCondition checked(Set<String> present, Set<String> absent, Map<String, String> values) {
    for (String name : absent) {
      if (present.contains(name) || values.containsKey(name)) {
        throw new IllegalArgumentException("A parameter can't be both required and absent: " + name);
      }
    }
    return new ParamCondition(present, absent, values);
  }

  @Override
  public Boolean apply(Map<CharSequence, List<String>> params) {
    for (String name : present) {
      if (!params.containsKey(name)) {
        return false;
      }
    }
    for (String name : absent) {
      if (params.containsKey(name)) {
        return false;
      }
    }
    for (Map.Entry<String, String> value : values.entrySet()) {
      List<String> actual = params.get(value.getKey());
      if (actual == null || actual.isEmpty() || !value.getValue().equals(actual.get(0))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The parameters of the smallest request that satisfies this condition: the required parameters, with empty values
   * unless a value is required, and nothing else.
   *
   * @return the parameters.
   */
  Map<CharSequence, List<String>> minimalParams() {
    Map<CharSequence, List<String>> params = new LinkedHashMap<>();
    present.forEach(name -> params.put(name, List.of("")));
    values.forEach((name, value) -> params.put(name, List.of(value)));
    return params;
  }

  @Override
  public String toString() {
    StringJoiner joiner = new StringJoiner("&", "?", "");
    present.forEach(joiner::add);
    values.forEach((name, value) -> joiner.add(name + "=" + value));
    absent.forEach(name -> joiner.add("!" + name));
    return joiner.toString();
  }

}
