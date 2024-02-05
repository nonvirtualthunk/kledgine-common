package arx.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntRangeSerializer : KSerializer<IntRange> {
    override fun deserialize(decoder: Decoder): IntRange {
        val arr = serializer.deserialize(decoder)
        return IntRange(arr[0], arr[1])
    }

    val serializer = IntArraySerializer()
    override val descriptor: SerialDescriptor = serializer.descriptor

    override fun serialize(encoder: Encoder, value: IntRange) {
        serializer.serialize(encoder, intArrayOf(value.first, value.last))
    }
}



//data class ValueRange(val low : Int?, val high: Int?) {
//    fun contains(v: Int) : Boolean {
//        return (low == null || low <= v) && (high == null || high >= v)
//    }
//
//    val lowOrMin : Int get() { return low ?: Int.MIN_VALUE }
//    val highOrMax : Int get() { return high ?: Int.MAX_VALUE }
//}
//
//
//object ValueRangeSerializer : KSerializer<ValueRange> {
//    val SentinelMin = Int.MIN_VALUE
//    val SentinelMax = Int.MAX_VALUE
//    override fun deserialize(decoder: Decoder): ValueRange {
//        val arr = serializer.deserialize(decoder)
//        return ValueRange(arr[0].takeIf { it != SentinelMin }, arr[1].takeIf { it != SentinelMax })
//    }
//
//    val serializer = IntArraySerializer()
//    override val descriptor: SerialDescriptor = serializer.descriptor
//
//    override fun serialize(encoder: Encoder, value: ValueRange) {
//        serializer.serialize(encoder, intArrayOf(value.low ?: SentinelMin, value.high ?: SentinelMax))
//    }
//}