// Copyright 2009 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Options;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsDiffForReconstruction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.python.PythonOptions;
import com.google.devtools.build.lib.skyframe.serialization.testutils.TestUtils;
import com.google.devtools.common.options.OptionsParser;
import java.util.AbstractMap;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A test for {@link BuildOptions}.
 *
 * <p>Currently this tests native options and skylark options completely separately since these two
 * types of options do not interact. In the future when we begin to migrate native options to
 * skylark options, the format of this test class will need to accommodate that overlap.
 */
@RunWith(JUnit4.class)
public class BuildOptionsTest {
  private static final ImmutableList<Class<? extends FragmentOptions>> BUILD_CONFIG_OPTIONS =
      ImmutableList.of(BuildConfiguration.Options.class);

  @Test
  public void optionSetCaching() {
    BuildOptions a =
        BuildOptions.of(BUILD_CONFIG_OPTIONS, OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS));
    BuildOptions b =
        BuildOptions.of(BUILD_CONFIG_OPTIONS, OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS));
    // The cache keys of the OptionSets must be equal even if these are
    // different objects, if they were created with the same options (no options in this case).
    assertThat(b.toString()).isEqualTo(a.toString());
    assertThat(b.computeCacheKey()).isEqualTo(a.computeCacheKey());
  }

  @Test
  public void cachingSpecialCases() throws Exception {
    // You can add options here to test that their string representations are good.
    String[] options = new String[] {"--run_under=//run_under"};
    BuildOptions a = BuildOptions.of(BUILD_CONFIG_OPTIONS, options);
    BuildOptions b = BuildOptions.of(BUILD_CONFIG_OPTIONS, options);
    assertThat(b.toString()).isEqualTo(a.toString());
  }

  @Test
  public void optionsEquality() throws Exception {
    String[] options1 = new String[] {"--compilation_mode=opt"};
    String[] options2 = new String[] {"--compilation_mode=dbg"};
    // Distinct instances with the same values are equal:
    assertThat(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1))
        .isEqualTo(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1));
    // Same fragments, different values aren't equal:
    assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(BuildOptions.of(BUILD_CONFIG_OPTIONS, options2)))
        .isFalse();
    // Same values, different fragments aren't equal:
    assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(
                    BuildOptions.of(
                        ImmutableList.<Class<? extends FragmentOptions>>of(
                            BuildConfiguration.Options.class, CppOptions.class),
                        options1)))
        .isFalse();
  }

  @Test
  public void optionsDiff() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8");
    BuildOptions three = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8");

    OptionsDiff diffOneTwo = BuildOptions.diff(one, two);
    OptionsDiff diffTwoThree = BuildOptions.diff(two, three);

    assertThat(diffOneTwo.areSame()).isFalse();
    assertThat(diffOneTwo.getFirst().keySet()).isEqualTo(diffOneTwo.getSecond().keySet());
    assertThat(diffOneTwo.prettyPrint()).contains("opt");
    assertThat(diffOneTwo.prettyPrint()).contains("dbg");

    assertThat(diffTwoThree.areSame()).isTrue();
  }

  @Test
  public void optionsDiff_differentFragments() throws Exception {
    BuildOptions one =
        BuildOptions.of(ImmutableList.<Class<? extends FragmentOptions>>of(CppOptions.class));
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsDiff diff = BuildOptions.diff(one, two);

    assertThat(diff.areSame()).isFalse();
    assertThat(diff.getExtraFirstFragmentClassesForTesting()).containsExactly(CppOptions.class);
    assertThat(
            diff.getExtraSecondFragmentsForTesting().stream()
                .map(Object::getClass)
                .collect(Collectors.toSet()))
        .containsExactlyElementsIn(BUILD_CONFIG_OPTIONS);
  }

  @Test
  public void optionsDiff_nullOptionsThrow() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = null;
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> BuildOptions.diff(one, two));
    assertThat(e).hasMessageThat().contains("Cannot diff null BuildOptions");
  }

  @Test
  public void optionsDiff_nullSecondValue() throws Exception {
    BuildOptions one = BuildOptions.of(ImmutableList.of(CppOptions.class), "--compiler=gcc");
    BuildOptions two = BuildOptions.of(ImmutableList.of(CppOptions.class));
    OptionsDiffForReconstruction diffForReconstruction =
        BuildOptions.diffForReconstruction(one, two);
    OptionsDiff diff = BuildOptions.diff(one, two);
    assertThat(diff.areSame()).isFalse();
    assertThat(diff.getSecond().values()).contains(null);
    BuildOptions reconstructed = one.applyDiff(diffForReconstruction);
    assertThat(reconstructed.get(CppOptions.class).cppCompiler).isNull();
  }

  @Test
  public void optionsDiff_differentBaseThrowException() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8");
    BuildOptions three = BuildOptions.of(ImmutableList.of(CppOptions.class), "--compiler=gcc");
    OptionsDiffForReconstruction diffForReconstruction =
        BuildOptions.diffForReconstruction(one, two);
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> three.applyDiff(diffForReconstruction));
    assertThat(e)
        .hasMessageThat()
        .contains("Can not reconstruct BuildOptions with a different base");
  }

  @Test
  public void optionsDiff_getEmptyAndApplyEmpty() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    OptionsDiffForReconstruction diffForReconstruction =
        BuildOptions.diffForReconstruction(one, two);
    BuildOptions reconstructed = one.applyDiff(diffForReconstruction);
    assertThat(one).isEqualTo(reconstructed);
  }

  @Test
  public void applyDiff_nativeOptions() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8");
    BuildOptions reconstructedTwo = one.applyDiff(BuildOptions.diffForReconstruction(one, two));
    assertThat(reconstructedTwo).isEqualTo(two);
    assertThat(reconstructedTwo).isNotSameAs(two);
    BuildOptions reconstructedOne = one.applyDiff(BuildOptions.diffForReconstruction(one, one));
    assertThat(reconstructedOne).isSameAs(one);
    BuildOptions otherFragment = BuildOptions.of(ImmutableList.of(CppOptions.class));
    assertThat(one.applyDiff(BuildOptions.diffForReconstruction(one, otherFragment)))
        .isEqualTo(otherFragment);
    assertThat(otherFragment.applyDiff(BuildOptions.diffForReconstruction(otherFragment, one)))
        .isEqualTo(one);
  }

  @Test
  public void optionsDiff_sameStarlarkOptions() throws Exception {
    Label flagName = Label.parseAbsoluteUnchecked("//foo/flag");
    String flagValue = "value";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagName, flagValue));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagName, flagValue));

    assertThat(BuildOptions.diff(one, two).areSame()).isTrue();
  }

  @Test
  public void optionsDiff_differentStarlarkOptions() throws Exception {
    Label flagName = Label.parseAbsoluteUnchecked("//bar/flag");
    String flagValueOne = "valueOne";
    String flagValueTwo = "valueTwo";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagName, flagValueOne));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagName, flagValueTwo));

    OptionsDiff diff = BuildOptions.diff(one, two);

    assertThat(diff.areSame()).isFalse();
    assertThat(diff.getStarlarkFirstForTesting().keySet())
        .isEqualTo(diff.getStarlarkSecondForTesting().keySet());
    assertThat(diff.getStarlarkFirstForTesting().keySet()).containsExactly(flagName);
    assertThat(diff.getStarlarkFirstForTesting().values()).containsExactly(flagValueOne);
    assertThat(diff.getStarlarkSecondForTesting().values()).containsExactly(flagValueTwo);
  }

  @Test
  public void optionsDiff_extraStarlarkOptions() throws Exception {
    Label flagNameOne = Label.parseAbsoluteUnchecked("//extra/flag/one");
    Label flagNameTwo = Label.parseAbsoluteUnchecked("//extra/flag/two");
    String flagValue = "foo";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagNameOne, flagValue));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagNameTwo, flagValue));

    OptionsDiff diff = BuildOptions.diff(one, two);

    assertThat(diff.areSame()).isFalse();
    assertThat(diff.getExtraStarlarkOptionsFirstForTesting()).containsExactly(flagNameOne);
    assertThat(diff.getExtraStarlarkOptionsSecondForTesting().entrySet())
        .containsExactly(Maps.immutableEntry(flagNameTwo, flagValue));
  }

  @Test
  public void applyDiff_sameStarlarkOptions() throws Exception {
    Label flagName = Label.parseAbsoluteUnchecked("//foo/flag");
    String flagValue = "value";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagName, flagValue));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagName, flagValue));

    BuildOptions reconstructedTwo = one.applyDiff(BuildOptions.diffForReconstruction(one, two));

    assertThat(reconstructedTwo).isEqualTo(two);
    assertThat(reconstructedTwo).isNotSameAs(two);

    BuildOptions reconstructedOne = one.applyDiff(BuildOptions.diffForReconstruction(one, one));

    assertThat(reconstructedOne).isSameAs(one);
  }

  @Test
  public void applyDiff_differentStarlarkOptions() throws Exception {
    Label flagName = Label.parseAbsoluteUnchecked("//bar/flag");
    String flagValueOne = "valueOne";
    String flagValueTwo = "valueTwo";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagName, flagValueOne));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagName, flagValueTwo));

    BuildOptions reconstructedTwo = one.applyDiff(BuildOptions.diffForReconstruction(one, two));

    assertThat(reconstructedTwo).isEqualTo(two);
    assertThat(reconstructedTwo).isNotSameAs(two);
  }

  @Test
  public void applyDiff_extraStarlarkOptions() throws Exception {
    Label flagNameOne = Label.parseAbsoluteUnchecked("//extra/flag/one");
    Label flagNameTwo = Label.parseAbsoluteUnchecked("//extra/flag/two");
    String flagValue = "foo";
    BuildOptions one = BuildOptions.of(ImmutableMap.of(flagNameOne, flagValue));
    BuildOptions two = BuildOptions.of(ImmutableMap.of(flagNameTwo, flagValue));

    BuildOptions reconstructedTwo = one.applyDiff(BuildOptions.diffForReconstruction(one, two));

    assertThat(reconstructedTwo).isEqualTo(two);
    assertThat(reconstructedTwo).isNotSameAs(two);
  }

  private static ImmutableList.Builder<Class<? extends FragmentOptions>> makeOptionsClassBuilder() {
    return ImmutableList.<Class<? extends FragmentOptions>>builder()
        .addAll(BUILD_CONFIG_OPTIONS)
        .add(CppOptions.class);
  }

  /**
   * Tests that an {@link OptionsDiffForReconstruction} serializes stably. Unfortunately, still
   * passes without fixes! (Perhaps more classes and diffs are needed?)
   */
  @Test
  public void codecStability() throws Exception {
    BuildOptions one =
        BuildOptions.of(
            makeOptionsClassBuilder()
                .add(AndroidConfiguration.Options.class)
                .add(ProtoConfiguration.Options.class)
                .build());
    BuildOptions two =
        BuildOptions.of(
            makeOptionsClassBuilder().add(JavaOptions.class).add(PythonOptions.class).build(),
            "--cpu=k8",
            "--compilation_mode=opt",
            "--compiler=gcc",
            "--copt=-Dfoo",
            "--javacopt=--javacoption",
            "--experimental_fix_deps_tool=fake",
            "--build_python_zip=no",
            "--python_version=PY2");
    OptionsDiffForReconstruction diff1 = BuildOptions.diffForReconstruction(one, two);
    OptionsDiffForReconstruction diff2 = BuildOptions.diffForReconstruction(one, two);
    assertThat(diff2).isEqualTo(diff1);
    assertThat(
            TestUtils.toBytes(
                diff2,
                ImmutableMap.of(
                    BuildOptions.OptionsDiffCache.class, new BuildOptions.DiffToByteCache())))
        .isEqualTo(
            TestUtils.toBytes(
                diff1,
                ImmutableMap.of(
                    BuildOptions.OptionsDiffCache.class, new BuildOptions.DiffToByteCache())));
  }

  @Test
  public void repeatedCodec() throws Exception {
    BuildOptions one = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8");
    BuildOptions two = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8");
    OptionsDiffForReconstruction diff = BuildOptions.diffForReconstruction(one, two);
    BuildOptions.OptionsDiffCache cache = new BuildOptions.FingerprintingKDiffToByteStringCache();
    assertThat(TestUtils.toBytes(diff, ImmutableMap.of(BuildOptions.OptionsDiffCache.class, cache)))
        .isEqualTo(
            TestUtils.toBytes(diff, ImmutableMap.of(BuildOptions.OptionsDiffCache.class, cache)));
  }

  @Test
  public void testMultiValueOptionImmutability() throws Exception {
    BuildOptions options =
        BuildOptions.of(BUILD_CONFIG_OPTIONS, OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS));
    BuildConfiguration.Options coreOptions = options.get(Options.class);
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            coreOptions.commandLineBuildVariables.add(new AbstractMap.SimpleEntry<>("foo", "bar")));
  }

  @Test
  public void parsingResultTransform() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo", "--stamp");

    OptionsParser parser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    parser.parse("--cpu=bar", "--nostamp");
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(original.get(BuildConfiguration.Options.class).cpu)
        .isNotEqualTo(modified.get(BuildConfiguration.Options.class).cpu);
    assertThat(modified.get(BuildConfiguration.Options.class).cpu).isEqualTo("bar");
    assertThat(modified.get(Options.class).stampBinaries).isFalse();
    assertThat(modified.getStarlarkOptions().get(Label.parseAbsoluteUnchecked("//custom:flag")))
        .isEqualTo("hello");
  }

  @Test
  public void parsingResultTransformNativeIgnored() throws Exception {
    ImmutableList.Builder<Class<? extends FragmentOptions>> fragmentClassesBuilder =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(BuildConfiguration.Options.class);

    BuildOptions original = BuildOptions.of(fragmentClassesBuilder.build());

    fragmentClassesBuilder.add(CppOptions.class);

    OptionsParser parser = OptionsParser.newOptionsParser(fragmentClassesBuilder.build());
    parser.parse("--cxxopt=bar");

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(modified.contains(CppOptions.class)).isFalse();
  }

  @Test
  public void parsingResultTransformIllegalStarlarkLabel() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    parser.setStarlarkOptions(ImmutableMap.of("@@@", "hello"));

    assertThrows(IllegalArgumentException.class, () -> original.applyParsingResult(parser));
  }

  @Test
  public void parsingResultTransformMultiValueOption() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    parser.parse("--features=foo");

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(modified.get(BuildConfiguration.Options.class).defaultFeatures)
        .containsExactly("foo");
  }

  @Test
  public void parsingResultMatch() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo", "--stamp");

    OptionsParser matchingParser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    matchingParser.parse("--cpu=foo", "--stamp");

    OptionsParser notMatchingParser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    notMatchingParser.parse("--cpu=foo", "--nostamp");

    assertThat(original.matches(matchingParser)).isTrue();
    assertThat(original.matches(notMatchingParser)).isFalse();
  }

  @Test
  public void parsingResultMatchStarlark() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseAbsoluteUnchecked("//custom:flag"), "hello")
            .build();

    OptionsParser matchingParser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    matchingParser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    OptionsParser notMatchingParser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    notMatchingParser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "foo"));

    assertThat(original.matches(matchingParser)).isTrue();
    assertThat(original.matches(notMatchingParser)).isFalse();
  }

  @Test
  public void parsingResultMatchMissingFragment() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo");

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(BuildConfiguration.Options.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.newOptionsParser(fragmentClasses);
    parser.parse("--cpu=foo", "--cxxopt=bar");

    assertThat(original.matches(parser)).isTrue();
  }

  @Test
  public void parsingResultMatchEmptyNativeMatch() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo");

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(BuildConfiguration.Options.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.newOptionsParser(fragmentClasses);
    parser.parse("--cxxopt=bar");

    assertThat(original.matches(parser)).isFalse();
  }

  @Test
  public void parsingResultMatchEmptyNativeMatchWithStarlark() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseAbsoluteUnchecked("//custom:flag"), "hello")
            .build();

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(BuildConfiguration.Options.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.newOptionsParser(fragmentClasses);
    parser.parse("--cxxopt=bar");
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    assertThat(original.matches(parser)).isTrue();
  }

  @Test
  public void parsingResultMatchStarlarkOptionMissing() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseAbsoluteUnchecked("//custom:flag1"), "hello")
            .build();

    OptionsParser parser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag2", "foo"));

    assertThat(original.matches(parser)).isFalse();
  }

  @Test
  public void parsingResultMatchNullOption() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.newOptionsParser(BUILD_CONFIG_OPTIONS);
    parser.parse("--platform_suffix=foo"); // Note: platform_suffix is null by default.

    assertThat(original.matches(parser)).isFalse();
  }
}
