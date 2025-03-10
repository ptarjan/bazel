// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.NULL_ACTION_OWNER;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.actions.util.DummyExecutor;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.UnixGlob;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A test for {@link CreateIncSymlinkAction}.
 */
@RunWith(JUnit4.class)
public class CreateIncSymlinkActionTest extends FoundationTestCase {

  private final ActionKeyContext actionKeyContext = new ActionKeyContext();

  @Test
  public void testDifferentOrderSameActionKey() {
    String outSegment = "out";
    Path includePath = rootDirectory.getRelative(outSegment);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, outSegment);
    Artifact a = ActionsTestUtil.createArtifact(root, "a");
    Artifact b = ActionsTestUtil.createArtifact(root, "b");
    Artifact c = ActionsTestUtil.createArtifact(root, "c");
    Artifact d = ActionsTestUtil.createArtifact(root, "d");
    CreateIncSymlinkAction action1 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b, c, d), includePath);
    // Can't reuse the artifacts here; that would lead to DuplicateArtifactException.
    a = ActionsTestUtil.createArtifact(root, "a");
    b = ActionsTestUtil.createArtifact(root, "b");
    c = ActionsTestUtil.createArtifact(root, "c");
    d = ActionsTestUtil.createArtifact(root, "d");
    CreateIncSymlinkAction action2 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(c, d, a, b), includePath);

    assertThat(computeKey(action2)).isEqualTo(computeKey(action1));
  }

  @Test
  public void testDifferentTargetsDifferentActionKey() {
    String outSegment = "out";
    Path includePath = rootDirectory.getRelative(outSegment);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, outSegment);
    Artifact a = ActionsTestUtil.createArtifact(root, "a");
    Artifact b = ActionsTestUtil.createArtifact(root, "b");
    CreateIncSymlinkAction action1 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), includePath);
    // Can't reuse the artifacts here; that would lead to DuplicateArtifactException.
    a = ActionsTestUtil.createArtifact(root, "a");
    b = ActionsTestUtil.createArtifact(root, "c");
    CreateIncSymlinkAction action2 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), includePath);

    assertThat(computeKey(action2)).isNotEqualTo(computeKey(action1));
  }

  @Test
  public void testDifferentSymlinksDifferentActionKey() {
    String outSegment = "out";
    Path includePath = rootDirectory.getRelative(outSegment);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, outSegment);
    Artifact a = ActionsTestUtil.createArtifact(root, "a");
    Artifact b = ActionsTestUtil.createArtifact(root, "b");
    CreateIncSymlinkAction action1 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), includePath);
    // Can't reuse the artifacts here; that would lead to DuplicateArtifactException.
    a = ActionsTestUtil.createArtifact(root, "c");
    b = ActionsTestUtil.createArtifact(root, "b");
    CreateIncSymlinkAction action2 =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), includePath);

    assertThat(computeKey(action2)).isNotEqualTo(computeKey(action1));
  }

  @Test
  public void testExecute() throws Exception {
    String outSegment = "out";
    Path outputDir = rootDirectory.getRelative(outSegment);
    outputDir.createDirectory();
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, outSegment);
    Path symlink = rootDirectory.getRelative("out/a");
    Artifact a = ActionsTestUtil.createArtifact(root, symlink);
    Artifact b = ActionsTestUtil.createArtifact(root, "b");
    CreateIncSymlinkAction action =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), outputDir);
    action.execute(makeDummyContext());
    symlink.stat(Symlinks.NOFOLLOW);
    assertThat(symlink.isSymbolicLink()).isTrue();
    assertThat(b.getPath().asFragment()).isEqualTo(symlink.readSymbolicLink());
    assertThat(rootDirectory.getRelative("a").exists()).isFalse();
  }

  private ActionExecutionContext makeDummyContext() {
    DummyExecutor executor = new DummyExecutor(fileSystem, rootDirectory);
    return new ActionExecutionContext(
        executor,
        /*actionInputFileCache=*/ null,
        /*actionInputPrefetcher=*/ null,
        /*actionKeyContext=*/ null,
        /*metadataHandler=*/ null,
        /*rewindingEnabled=*/ false,
        LostInputsCheck.NONE,
        /*fileOutErr=*/ null,
        new StoredEventHandler(),
        /*clientEnv=*/ ImmutableMap.of(),
        /*topLevelFilesets=*/ ImmutableMap.of(),
        /*artifactExpander=*/ null,
        /*actionFileSystem=*/ null,
        /*skyframeDepsResult=*/ null,
        DiscoveredModulesPruner.DEFAULT,
        UnixGlob.DEFAULT_SYSCALLS,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  @Test
  public void testFileRemoved() throws Exception {
    String outSegment = "out";
    Path outputDir = rootDirectory.getRelative(outSegment);
    outputDir.createDirectory();
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(rootDirectory, RootType.Output, outSegment);
    Path symlink = rootDirectory.getRelative("out/subdir/a");
    Artifact a = ActionsTestUtil.createArtifact(root, symlink);
    Artifact b = ActionsTestUtil.createArtifact(root, "b");
    CreateIncSymlinkAction action =
        new CreateIncSymlinkAction(NULL_ACTION_OWNER, symlinksMap(a, b), outputDir);
    Path extra = rootDirectory.getRelative("out/extra");
    FileSystemUtils.createEmptyFile(extra);
    assertThat(extra.exists()).isTrue();
    action.prepare(
        rootDirectory,
        ArtifactPathResolver.IDENTITY,
        /*bulkDeleter=*/ null,
        /*cleanupArchivedArtifacts=*/ false);
    assertThat(extra.exists()).isFalse();
  }

  private String computeKey(CreateIncSymlinkAction action) {
    Fingerprint fp = new Fingerprint();
    action.computeKey(actionKeyContext, /*artifactExpander=*/ null, fp);
    return fp.hexDigestAndReset();
  }

  private static ImmutableSortedMap<Artifact, Artifact> symlinksMap(Artifact... artifacts) {
    checkArgument(artifacts.length % 2 == 0, "Odd number of arguments: %s", artifacts.length);
    ImmutableSortedMap.Builder<Artifact, Artifact> symlinks =
        ImmutableSortedMap.orderedBy(Artifact.EXEC_PATH_COMPARATOR);
    for (int i = 0; i < artifacts.length; i += 2) {
      symlinks.put(artifacts[i], artifacts[i + 1]);
    }
    return symlinks.buildOrThrow();
  }
}
