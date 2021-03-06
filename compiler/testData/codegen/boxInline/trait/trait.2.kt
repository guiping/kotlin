package test

interface InlineTrait {

    fun finalInline(s: () -> String): String {
        return s()
    }

    companion object {
        inline final fun finalInline(s: () -> String): String {
            return s()
        }
    }
}

class Z: InlineTrait {

}