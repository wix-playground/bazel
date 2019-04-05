// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.skylark;

import static com.google.devtools.build.lib.analysis.skylark.FunctionTransitionUtil.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.transitions.ComposingTransition;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.syntax.Type.ConversionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** A marker class for configuration transitions that are defined in Starlark. */
public abstract class StarlarkTransition implements ConfigurationTransition {

  private final StarlarkDefinedConfigTransition starlarkDefinedConfigTransition;

  public StarlarkTransition(StarlarkDefinedConfigTransition starlarkDefinedConfigTransition) {
    this.starlarkDefinedConfigTransition = starlarkDefinedConfigTransition;
  }

  public void replayOn(ExtendedEventHandler eventHandler) {
    starlarkDefinedConfigTransition.getEventHandler().replayOn(eventHandler);
    starlarkDefinedConfigTransition.getEventHandler().clear();
  }

  public boolean hasErrors() {
    return starlarkDefinedConfigTransition.getEventHandler().hasErrors();
  }

  private List<String> getOutputs() {
    return starlarkDefinedConfigTransition.getOutputs();
  }

  /** Exception class for exceptions thrown during application of a starlark-defined transition */
  public static class TransitionException extends Exception {
    private final String message;

    public TransitionException(String message) {
      this.message = message;
    }

    public TransitionException(Throwable cause) {
      this.message = cause.getMessage();
    }

    /** Returns the error message. */
    @Override
    public String getMessage() {
      return message;
    }
  }

  /**
   * For a given transition, find all Starlark-defined build settings that were set by that
   * transition. Then return all package keys for those flags.
   *
   * <p>Currently this method does not handle the possibility of aliased build settings. We may not
   * actually load the package that actually contains the build setting but we won't know until we
   * fetch the actual target.
   */
  // TODO(juliexxia): handle the possibility of aliased build settings.
  public static ImmutableSet<SkyKey> getBuildSettingPackageKeys(ConfigurationTransition root) {
    ImmutableSet.Builder<SkyKey> keyBuilder = new ImmutableSet.Builder<>();
    try {
      root.visit(
          (StarlarkTransitionVisitor)
              transition -> {
                for (Label setting : getChangedStarlarkSettings(transition)) {
                  keyBuilder.add(PackageValue.key(setting.getPackageIdentifier()));
                }
              });
    } catch (TransitionException e) {
      // Not actually thrown in the visitor, but declared.
    }
    return keyBuilder.build();
  }

  /**
   * Method to be called after Starlark-transitions are applied. Handles events and checks outputs.
   *
   * <p>Logs any events (e.g. {@code print()}s, errors} to output and throws an error if we had any
   * errors. Right now, Starlark transitions are the only kind that knows how to throw errors so we
   * know this will only report and throw if a Starlark transition caused a problem.
   *
   * <p>We only do validation on Starlark-defined build settings. Native options (designated with
   * {@code COMMAND_LINE_OPTION_PREFIX}) already have their output values checked in {@link
   * FunctionTransitionUtil#applyTransition}.
   *
   * @param root transition that was applied. Likely a {@link ComposingTransition} so we decompose
   *     and post-process all StarlarkTransitions out of whatever transition is passed here.
   * @param buildSettingPackages SkyKeys/Values of packages that contain all Starlark-defined build
   *     settings that were set by {@code root}
   * @param toOptions result of applying {@code root}
   * @param eventHandler eventHandler on which to replay events
   * @throws TransitionException if an error occurred during Starlark transition application.
   */
  // TODO(juliexxia): the current implementation masks certain bad transitions and only checks the
  // final result. I.e. if a transition that writes a non int --//int-build-setting is composed
  // with another transition that writes --//int-build-setting (without reading it first), then
  // the bad output of transition 1 is masked.
  public static void validate(
      ConfigurationTransition root,
      Map<SkyKey, SkyValue> buildSettingPackages,
      List<BuildOptions> toOptions,
      ExtendedEventHandler eventHandler)
      throws TransitionException {
    replayEvents(eventHandler, root);

    // collect settings changed during this transition and their types
    Map<Label, Type<?>> changedSettingToType = Maps.newHashMap();
    root.visit(
        (StarlarkTransitionVisitor)
            transition -> {
              List<Label> changedSettings = getChangedStarlarkSettings(transition);
              for (Label setting : changedSettings) {
                Package buildSettingPackage =
                    ((PackageValue)
                            buildSettingPackages.get(
                                PackageValue.key(setting.getPackageIdentifier())))
                        .getPackage();
                Target buildSettingTarget;
                try {
                  buildSettingTarget = buildSettingPackage.getTarget(setting.getName());
                } catch (NoSuchTargetException e) {
                  throw new TransitionException(e);
                }
                if (buildSettingTarget.getAssociatedRule() == null
                    || buildSettingTarget.getAssociatedRule().getRuleClassObject().getBuildSetting()
                        == null) {
                  throw new TransitionException(
                      String.format(
                          "attempting to transition on '%s' which is not a build setting",
                          setting));
                }
                changedSettingToType.put(
                    setting,
                    buildSettingTarget
                        .getAssociatedRule()
                        .getRuleClassObject()
                        .getBuildSetting()
                        .getType());
              }
            });

    // verify changed settings were changed to something reasonable for their type
    for (BuildOptions options : toOptions) {
      for (Map.Entry<Label, Type<?>> changedSettingWithType : changedSettingToType.entrySet()) {
        Label setting = changedSettingWithType.getKey();
        Object newValue = options.getStarlarkOptions().get(setting);
        try {
          changedSettingWithType.getValue().convert(newValue, setting);
        } catch (ConversionException e) {
          throw new TransitionException(e);
        }
      }
    }
  }

  private static List<Label> getChangedStarlarkSettings(StarlarkTransition transition) {
    return transition.getOutputs().stream()
        .filter(setting -> !setting.startsWith(COMMAND_LINE_OPTION_PREFIX))
        .map(Label::parseAbsoluteUnchecked)
        .collect(Collectors.toList());
  }

  /**
   * For a given transition, for any Starlark-defined transitions that compose it, replay events. If
   * any events were errors, throw an error.
   */
  private static void replayEvents(ExtendedEventHandler eventHandler, ConfigurationTransition root)
      throws TransitionException {
    root.visit(
        (StarlarkTransitionVisitor)
            transition -> {
              // Replay events and errors and throw if there were errors
              boolean hasErrors = transition.hasErrors();
              transition.replayOn(eventHandler);
              if (hasErrors) {
                throw new TransitionException(
                    "Errors encountered while applying Starlark transition");
              }
            });
  }

  @Override
  public boolean equals(Object object) {
    if (object == this) {
      return true;
    }
    if (object instanceof StarlarkTransition) {
      StarlarkDefinedConfigTransition starlarkDefinedConfigTransition =
          ((StarlarkTransition) object).starlarkDefinedConfigTransition;
      return Objects.equals(starlarkDefinedConfigTransition, this.starlarkDefinedConfigTransition);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(starlarkDefinedConfigTransition);
  }

  @FunctionalInterface
  // This is only used in this class to handle the cast and the exception
  @SuppressWarnings("FunctionalInterfaceMethodChanged")
  private interface StarlarkTransitionVisitor
      extends ConfigurationTransition.Visitor<TransitionException> {
    @Override
    default void accept(ConfigurationTransition transition) throws TransitionException {
      if (transition instanceof StarlarkTransition) {
        this.accept((StarlarkTransition) transition);
      }
    }

    void accept(StarlarkTransition transition) throws TransitionException;
  }
}
