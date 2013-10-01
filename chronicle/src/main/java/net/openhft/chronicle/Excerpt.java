/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle;

import org.jetbrains.annotations.NotNull;

/**
 * @author peter.lawrey
 */
public interface Excerpt extends ExcerptTailer {
	/**
	 * Randomly select an Excerpt.
	 * 
	 * @param l
	 *            index to look up
	 * @return true if this is a valid entries and not padding.
	 */
	boolean index(long l);

	/**
	 * Replay from the start.
	 * 
	 * @return this Excerpt
	 */
	@NotNull
	Excerpt toStart();

	/**
	 * Wind to the end.
	 * 
	 * @return this Excerpt
	 */
	@NotNull
	Excerpt toEnd();
}
