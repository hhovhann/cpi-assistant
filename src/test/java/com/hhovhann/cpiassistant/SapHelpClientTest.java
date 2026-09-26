package com.hhovhann.cpiassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a saved SAP page looks like after cleaning — shapes taken from real pages. */
class SapHelpClientTest {

    @Test
    void htmlTablesBecomeOneLinePerRow() {
        String page = """
                **Configure the Connection Details as Per the Description**

                <table>
                <tr>
                <th valign="top">

                Field

                </th>
                <th valign="top">

                Description

                </th>
                </tr>
                <tr>
                <td valign="top">

                *Connection Timeout \\(in s\\)*

                </td>
                <td valign="top">

                Provide a connection timeout in seconds.<br/>The default is 10.

                </td>
                </tr>
                </table>
                """;

        assertThat(SapHelpClient.clean(page)).isEqualTo("""
                **Configure the Connection Details as Per the Description**

                Field | Description

                *Connection Timeout (in s)* | Provide a connection timeout in seconds. The default is 10.""");
    }

    @Test
    void commentsAnchorsImagesAndLinksAreRemovedButLinkTextStays() {
        String page = """
                <!-- loio88be644 -->
                # JDBC Receiver Adapter
                <a name="loio88be644__section"/>
                See [Configure JDBC Drivers](configure-jdbc-drivers-77c7d95.md).
                ![Diagram](images/jdbc.png)
                """;

        assertThat(SapHelpClient.clean(page)).isEqualTo("""
                # JDBC Receiver Adapter

                See Configure JDBC Drivers.""");
    }
}
