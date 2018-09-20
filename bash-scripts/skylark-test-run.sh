#!/usr/bin/env bash
cd ..
bazel test -s --cache_test_results=no //src/test/shell/bazel:skylark_git_repository_test