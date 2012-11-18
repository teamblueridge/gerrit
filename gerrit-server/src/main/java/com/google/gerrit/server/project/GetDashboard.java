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

package com.google.gerrit.server.project;

import static com.google.gerrit.server.git.GitRepositoryManager.REFS_DASHBOARDS;

import com.google.gerrit.extensions.restapi.RestReadView;

import java.io.UnsupportedEncodingException;

class GetDashboard implements RestReadView<DashboardResource> {
  @Override
  public Object apply(DashboardResource resource)
      throws UnsupportedEncodingException {
    return DashboardsCollection.parse(
        resource.getControl().getProject(),
        resource.getRefName().substring(REFS_DASHBOARDS.length()),
        resource.getPathName(),
        resource.getConfig());
  }
}