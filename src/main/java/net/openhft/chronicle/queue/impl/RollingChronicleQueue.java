/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.queue.impl;

import net.openhft.chronicle.core.annotation.Nullable;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.RollCycle;
import org.jetbrains.annotations.NotNull;

public interface RollingChronicleQueue extends ChronicleQueue {

    long epoch();

    /**
     * @param cycle the cycle
     * @param epoch an epoch offset as the number of number of milliseconds since January 1, 1970,
     *              00:00:00 GMT
     *              @param createIfAbsent create missing stores if true, or return null if missing
     * @return the {@code WireStore} associated with this {@code cycle}, or null if !createIfAbsent is false and absent
     */
    @Nullable
    WireStore storeForCycle(int cycle, final long epoch, boolean createIfAbsent);

    /**
     * @param store the {@code store} to release
     */
    void release(@NotNull WireStore store);

    /**
     * @return the first cycle number found, or Integer.MAX_VALUE is none found.
     */
    int firstCycle();

    /**
     * @return the lastCycle available or Integer.MIN_VALUE if none is found.
     */
    int lastCycle();

    /**
     * @return the current cycle
     */
    int cycle();

    RollCycle rollCycle();
}
