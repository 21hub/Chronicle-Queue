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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import net.openhft.chronicle.tools.ChronicleTools;

import org.junit.Ignore;
import org.junit.Test;

/**
 * User: plawrey
 * Date: 26/09/13
 * Time: 17:48
 */
public class RollingChronicleTest {
	@Test
	@Ignore
	public void testAppending() throws IOException {
		int counter = 0;
		String basePath = System.getProperty("java.io.tmpdir") + "/testAppending";
		ChronicleTools.deleteDirOnExit(basePath);
		for (int k = 0; k < 15; k++) {
			if (k == 14)
				Thread.yield();
			;
			RollingChronicle rc = new RollingChronicle(basePath, ChronicleConfig.TEST);
			ExcerptAppender appender = rc.createAppender();
			assertEquals("k: " + k, (long) counter, appender.size());
			for (int i = 0; i < 1/* ChronicleConfig.TEST.indexFileExcerpts()/3 */; i++) {
				appender.startExcerpt(4);
				appender.writeInt(counter++);
				appender.finish();
				assertEquals("k: " + k + ", i: " + i, (long) counter, appender.size());
			}
			appender.close();
			rc.close();
		}
		// counter = 8192*2;

		RollingChronicle rc = new RollingChronicle(basePath, ChronicleConfig.TEST);
		ExcerptTailer tailer = rc.createTailer();
		for (int i = 0; i < counter; i++) {
			assertTrue("i: " + i, tailer.nextIndex());
			assertEquals(i, tailer.readInt());
			tailer.finish();
		}
		rc.close();

	}
}
