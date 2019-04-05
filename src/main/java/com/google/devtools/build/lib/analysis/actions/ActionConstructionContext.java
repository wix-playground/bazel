// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions;

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;

/**
 * Contains all API required to construct actions in the context of some target or an aspect applied
 * to a target. Depend on this interface instead of the full RuleContext to avoid accidental data
 * dependency on attribute values.
 *
 * <p>This a "native" equivalent of Starlark's `ctx.actions`.
 */
public interface ActionConstructionContext {

  /** Returns the bin directory for constructed actions. */
  ArtifactRoot getBinDirectory();

  /** Returns the internal directory (used for middlemen) for constructed actions. */
  ArtifactRoot getMiddlemanDirectory();

  /** Returns the action owner that should be used for actions. */
  ActionOwner getActionOwner();

  /** Returns the action key context. */
  ActionKeyContext getActionKeyContext();

  /** Returns the {@link BuildConfiguration} for which the given rule is analyzed. */
  BuildConfiguration getConfiguration();

  /** The current analysis environment. */
  AnalysisEnvironment getAnalysisEnvironment();

  void registerAction(ActionAnalysisMetadata... actions);

  /**
   * Creates an artifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  Artifact getDerivedArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Creates a TreeArtifact under a given root with the given root-relative path.
   *
   * <p>Verifies that it is in the root-relative directory corresponding to the package of the rule,
   * thus ensuring that it doesn't clash with other artifacts generated by other rules using this
   * method.
   */
  SpecialArtifact getTreeArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Returns an artifact that can be an output of shared actions. Only use when there is no other
   * option.
   *
   * <p>This artifact can be created anywhere in the output tree, which, in addition to making
   * sharing possible, opens up the possibility of action conflicts and makes it impossible to infer
   * the label of the rule creating the artifact from the path of the artifact.
   */
  Artifact getShareableArtifact(PathFragment rootRelativePath, ArtifactRoot root);

  /**
   * Returns the implicit output artifact for a given template function. If multiple or no artifacts
   * can be found as a result of the template, an exception is thrown.
   */
  Artifact getImplicitOutputArtifact(ImplicitOutputsFunction function) throws InterruptedException;

  /**
   * Returns an artifact with a given file extension. All other path components are the same as in
   * {@code pathFragment}.
   */
  Artifact getRelatedArtifact(PathFragment pathFragment, String extension);

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   *
   * @param uniqueDirectorySuffix suffix of the directory - it will be prepended
   */
  Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, String relative);

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   *
   * @param uniqueDirectorySuffix suffix of the directory - it will be prepended
   */
  Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, PathFragment relative);

  /**
   * Creates an artifact in a directory that is unique to the rule, thus guaranteeing that it never
   * clashes with artifacts created by other rules.
   */
  Artifact getUniqueDirectoryArtifact(
      String uniqueDirectory, PathFragment relative, ArtifactRoot root);

  /**
   * Returns a path fragment qualified by the rule name and unique fragment to
   * disambiguate artifacts produced from the source file appearing in
   * multiple rules.
   *
   * <p>For example "pkg/dir/name" -> "pkg/&lt;fragment>/rule/dir/name.
   */
  public PathFragment getUniqueDirectory(PathFragment fragment);

  /**
   * Returns the root of either the "bin" or "genfiles" tree, based on this target and the current
   * configuration. The choice of which tree to use is based on the rule with which this target
   * (which must be an OutputFile or a Rule) is associated.
   */
  public ArtifactRoot getBinOrGenfilesDirectory();

  /**
   * Returns the root-relative path fragment under which output artifacts of this rule should go.
   *
   * <p>Note that:
   *
   * <ul>
   *   <li>This doesn't guarantee that there are no clashes with rules in the same package.
   *   <li>If possible, {@link #getPackageRelativeArtifact(PathFragment, ArtifactRoot)} should be
   *       used instead of this method.
   * </ul>
   *
   * Ideally, user-visible artifacts should all have corresponding output file targets, all others
   * should go into a rule-specific directory. {@link #getUniqueDirectoryArtifact(String, String)})
   * ensures that this is the case.
   */
  PathFragment getPackageDirectory();

  /**
   * Creates an artifact in a directory that is unique to the package that contains the rule, thus
   * guaranteeing that it never clashes with artifacts created by rules in other packages.
   */
  Artifact getPackageRelativeArtifact(PathFragment relative, ArtifactRoot root);

  /** Returns the {@link PlatformInfo} describing the execution platform this action should use. */
  @Nullable
  PlatformInfo getExecutionPlatform();

  /**
   * Returns the {@link com.google.devtools.build.lib.packages.RuleErrorConsumer} for reporting rule
   * errors.
   */
  RuleErrorConsumer getRuleErrorConsumer();
}
