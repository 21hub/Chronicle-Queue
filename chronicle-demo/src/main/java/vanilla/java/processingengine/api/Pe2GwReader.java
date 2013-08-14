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

package vanilla.java.processingengine.api;

import net.openhft.chronicle.ExcerptTailer;

/**
 * @author peter.lawrey
 */
public class Pe2GwReader {
    private final int sourceId;
    private final ExcerptTailer excerpt;
    private final Pe2GwEvents gwEvents;
    private final MetaData metaData = new MetaData(false);
    private final SmallReport report = new SmallReport();

    public Pe2GwReader(int sourceId, ExcerptTailer excerpt, Pe2GwEvents gwEvents) {
        this.sourceId = sourceId;
        this.excerpt = excerpt;
        this.gwEvents = gwEvents;
    }

    public boolean readOne() {
        if (!excerpt.nextIndex()) return false;

        long pos = excerpt.position();
        System.out.println("Reading " + excerpt.index());
        MessageType mt = excerpt.readEnum(MessageType.class);
        if (mt == null) {
            // rewind and read again.
            excerpt.position(pos);
            System.err.println("Unknown message type " + excerpt.readUTF());
            return true;
        }
        switch (mt) {
            case report: {
                metaData.readFromEngine(excerpt, sourceId);
                report.readMarshallable(excerpt);
                gwEvents.report(metaData, report);
                break;
            }
            default:
                System.err.println("Unknown message type " + mt);
                break;
        }
        return true;
    }
}
