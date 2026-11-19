package org.campana.agent.agent

import android.content.res.AssetManager

object SkillLibrary {
    val names=listOf("product-marketing","content-strategy","social","copywriting","analytics","revops","ab-testing","ad-creative")
    fun load(assets:AssetManager):String = names.joinToString("\n\n") { name->
        assets.open("skills/"+name+"/SKILL.md").bufferedReader().use { it.readText() }
    }
}
