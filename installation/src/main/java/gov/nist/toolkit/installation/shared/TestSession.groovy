package gov.nist.toolkit.installation.shared

import com.google.gwt.user.client.rpc.IsSerializable

//@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
class TestSession implements Serializable, IsSerializable {
    private static final long serialVersionUID = 1L

    String value
    public static final transient TestSession DEFAULT_TEST_SESSION = new TestSession("default")
    public static final transient TestSession GAZELLE_TEST_SESSION = new TestSession("gazelle")
    public static final transient TestSession CAT_TEST_SESSION = new TestSession("cat")

    private TestSession() {}

    TestSession(String value) {
        this.value = value
    }

    String getValue() {
        return value
    }

    @Override
    String toString() {
        return value
    }

    void clean() { value = value.replaceAll("\\.", "_").toLowerCase() }

    @Override
    boolean equals(Object o) {
        if (this == o) return true
        if (o == null || getClass() != o.getClass()) return false

        TestSession that = (TestSession) o

        return value != null ? value.equals(that.value) : that.value == null
    }

    @Override
    int hashCode() {
        return value != null ? value.hashCode() : 0
    }
}
