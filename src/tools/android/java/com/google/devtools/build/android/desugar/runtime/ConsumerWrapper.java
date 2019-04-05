// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.android.desugar.runtime;

import android.telephony.AvailableNetworkInfo;
import android.telephony.TelephonyManager;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Conversion from desugared to built-in {@link Consumer} for calling built-in Android APIs (see
 * b/128638076).
 */
// TODO(b/74087778): Unnecessary when j$.u.f.Consumer becomes subtype of built-in j.u.f.Consumer
@SuppressWarnings("AndroidJdkLibsChecker")
public final class ConsumerWrapper<T> implements Consumer<T> {

  private final j$.util.function.Consumer<T> wrapped;

  private ConsumerWrapper(j$.util.function.Consumer<T> wrapped) {
    this.wrapped = wrapped;
  }

  @Override
  public void accept(T t) {
    wrapped.accept(t);
  }

  public static void setPreferredOpportunisticDataSubscription(
      TelephonyManager receiver,
      int subId,
      boolean needValidation,
      Executor executor,
      j$.util.function.Consumer<Integer> callback) {
    receiver.setPreferredOpportunisticDataSubscription(
        subId,
        needValidation,
        executor,
        callback != null ? new ConsumerWrapper<Integer>(callback) : null);
  }

  public static void updateAvailableNetworks(
      TelephonyManager receiver,
      List<AvailableNetworkInfo> availableNetworks,
      Executor executor,
      j$.util.function.Consumer<Integer> callback) {
    receiver.updateAvailableNetworks(
        availableNetworks,
        executor,
        callback != null ? new ConsumerWrapper<Integer>(callback) : null);
  }
}
