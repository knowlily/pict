package android.util;

/**
 * JVM 单测替身：AGP 的 mockable android.jar 里 {@code android.util.Pair} 的构造器是空壳，
 * 字段恒为 null —— 于是任何依赖它的库代码都会在 `pair.first` 上 NPE
 * （androidx ExifInterface 的 setAttribute 就死在这里）。
 *
 * 这个类只在单元测试编译/运行期使用（放在 src/test/java 下，main 不参与），
 * 单测的 classes 输出目录排在 classpath 最前，因此优先于 mockable jar 被加载。
 * 它只补「构造器真的赋值」这一点，语义与真机上的 android.util.Pair 一致。
 */
public final class Pair<F, S> {

    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }

    public static <A, B> Pair<A, B> create(A a, B b) {
        return new Pair<>(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Pair)) {
            return false;
        }
        Pair<?, ?> other = (Pair<?, ?>) o;
        return java.util.Objects.equals(first, other.first)
                && java.util.Objects.equals(second, other.second);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(first) * 31 + java.util.Objects.hashCode(second);
    }

    @Override
    public String toString() {
        return "Pair{" + first + ", " + second + "}";
    }
}
