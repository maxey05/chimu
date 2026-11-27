package com.chimu.ui

/**
 * Which tab is showing, in the order they sit in the bar: two on the left of +, two on the right (v6 design).
 * Plan lives inside Dashboard now, and Profile is opened from the account menu on Home, so neither is a tab.
 */
enum class NavTab { HOME, TRANSACTIONS, DASHBOARD, GROUP }
