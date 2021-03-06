// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git;

import static com.google.gerrit.server.git.MergeUtil.canFastForward;
import static com.google.gerrit.server.git.MergeUtil.canMerge;
import static com.google.gerrit.server.git.MergeUtil.getFirstFastForward;
import static com.google.gerrit.server.git.MergeUtil.markCleanMerges;
import static com.google.gerrit.server.git.MergeUtil.mergeOneCommit;
import static com.google.gerrit.server.git.MergeUtil.reduceToMinimalMerge;

import com.google.gerrit.reviewdb.client.PatchSetApproval;

import java.util.List;

public class MergeIfNecessary extends SubmitStrategy {

  MergeIfNecessary(final SubmitStrategy.Arguments args) {
    super(args);
  }

  @Override
  protected CodeReviewCommit _run(final CodeReviewCommit mergeTip,
      final List<CodeReviewCommit> toMerge) throws MergeException {
    reduceToMinimalMerge(args.mergeSorter, toMerge);
    CodeReviewCommit newMergeTip =
        getFirstFastForward(mergeTip, args.rw, toMerge);

    // For every other commit do a pair-wise merge.
    while (!toMerge.isEmpty()) {
      newMergeTip =
          mergeOneCommit(args.db, args.identifiedUserFactory, args.myIdent,
              args.repo, args.rw, args.inserter, args.canMergeFlag,
              args.useContentMerge, args.destBranch, mergeTip,
              toMerge.remove(0));
    }

    final PatchSetApproval submitApproval =
        markCleanMerges(args.db, args.rw, args.canMergeFlag, newMergeTip,
            args.alreadyAccepted);
    setRefLogIdent(submitApproval);

    return newMergeTip;
  }

  @Override
  public boolean dryRun(final CodeReviewCommit mergeTip,
      final CodeReviewCommit toMerge) throws MergeException {
    return canFastForward(args.mergeSorter, mergeTip, args.rw, toMerge)
        || canMerge(args.mergeSorter, args.repo, args.useContentMerge,
            mergeTip, toMerge);
  }
}
