package arx.game.core

import arx.core.*
import arx.engine.CreateOnAccessData
import arx.engine.DataType
import arx.engine.GameData
import com.typesafe.config.ConfigValue
import kotlinx.serialization.Serializable

@Serializable
class Flags(private var raw : Map<Taxon, Int> = emptyMap(), private var rawKeyed : Map<Taxon, Map<Taxon, Int>> = emptyMap()) : FromConfig {
    companion object : FromConfigCreator<Flags> {
        override fun createFromConfig(cv: ConfigValue?): Flags? {
            if (cv == null) { return null }
            return Flags().apply { readFromConfig(cv) }
        }

        operator fun invoke(vararg pairs: Pair<Taxon, Int>) : Flags {
            return Flags(raw = mapOf(*pairs))
        }
    }

    override fun readFromConfig(cv: ConfigValue) {
        for ((k, v) in cv) {
            if (v.isBool()) {
                raw = raw + (t(k) to if (v.asBool() == true) {
                    1
                } else {
                    0
                })
            } else if (v.isObject()) {
                var subMap = rawKeyed[t(k)] ?: emptyMap()
                for ((sk, sv) in v) {
                    if (sv.isBool()) {
                        subMap = subMap + (t(sk) to if (sv.asBool() == true) {
                            1
                        } else {
                            0
                        })
                    } else if (sv.isInt()) {
                        subMap = subMap + (t(sk) to (sv.asInt() ?: -1))
                    } else {
                        Noto.warn("Unexpected config type for keyed flag, did you miss some quotes? $k, $sk, $v")
                    }
                }
            } else if (v.isInt()) {
                v.asInt()?.expectLet {
                    raw = raw + (t(k) to it)
                }
            } else {
                Noto.warn("Unexpected config type in flags : $v")
            }
        }
    }


    fun merge(other : Flags) {
        for ((k,v) in other.raw) {
            this.raw = this.raw + (k to (this.raw.getOrDefault(k, 0) + v))
        }
        for ((k,v) in other.rawKeyed) {
            var thisMap = this.rawKeyed[k] ?: emptyMap()
            for ((sk,sv) in v) {
                thisMap = thisMap + (sk to (thisMap.getOrDefault(sk, 0) + sv))
            }
        }
    }

    fun mergeMax(other : Flags) {
        if (other.raw.isEmpty() && other.rawKeyed.isEmpty()) {
            return
        } else if (this.raw.isEmpty() && this.rawKeyed.isEmpty()) {
            this.raw = other.raw
            this.rawKeyed = other.rawKeyed
        } else {
            for (k in this.raw.keys + other.raw.keys) {
                if (other.raw.getOrDefault(k, 0) > this.raw.getOrDefault(k, 0)) {
                    this.raw = this.raw + (k to other.raw.getOrDefault(k, 0))
                }
            }

            for (k in this.rawKeyed.keys + other.rawKeyed.keys) {
                var thisMap = this.rawKeyed.getOrDefault(k, emptyMap())
                val otherMap = other.rawKeyed.getOrDefault(k, emptyMap())
                for (sk in thisMap.keys + otherMap.keys) {
                    thisMap = thisMap + (sk to thisMap.getOrDefault(sk, 0).max(otherMap.getOrDefault(sk, 0)))
                }
                this.rawKeyed = this.rawKeyed + (k to thisMap)
            }
        }
    }

    fun copy() : Flags {
        return Flags(this.raw, this.rawKeyed)
    }

    operator fun plus(other: Flags) : Flags {
        val ret = Flags(raw = this.raw, rawKeyed = this.rawKeyed)
        ret.merge(other)
        return ret
    }


    fun setFlag(f : Taxon, value : Int) {
        if (this.raw[f] != value) {
            this.raw = this.raw + (f to value)
        }
    }

    fun unsetFlag(f: Taxon) {
        if (this.raw.containsKey(f)) {
            this.raw = this.raw - f
        }
    }

    fun containsFlag(f: Taxon) : Boolean {
        return this.raw.containsKey(f)
    }

    fun increaseFlag(f : Taxon, value : Int) {
        this.raw = this.raw + (f to this.raw.getOrDefault(f, 0) + value)
    }

    fun decreaseFlag(f : Taxon, value : Int) {
        this.raw = this.raw + (f to this.raw.getOrDefault(f, 0) - value)
    }

    fun keyedValues(f: Taxon) : Map<Taxon, Int> {
        return this.rawKeyed.getOrDefault(f, emptyMap())
    }

    fun flagValue(f: Taxon) : Int {
        return this.raw.getOrDefault(f, 0)
    }

    fun flagValues(): Iterator<Pair<Taxon, Int>> {
        return iterator {
            for ((k,v) in raw) {
                yield(k to v)
            }
        }
    }
}

data class FlagData(
    var flags: Flags = Flags()
) : GameData, FromConfig, CreateOnAccessData {
    companion object : DataType<FlagData>(FlagData(), sparse = false), FromConfigCreator<FlagData> {
        override fun createFromConfig(cv: ConfigValue?): FlagData? {
            if (cv == null) { return null }
            return FlagData().apply { readFromConfig(cv) }
        }
    }

    override fun dataType(): DataType<*> {
        return FlagData
    }

    override fun readFromConfig(cv: ConfigValue) {
        flags = Flags.createFromConfig(cv["flags"]) ?: Flags()
    }
}
