package main

import "strings"

// System describe una carpeta de plataforma con los nombres de ES-DE (los mismos que usa Cocoon).
type System struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Short string `json:"short"`
	Maker string `json:"maker"`
	Color string `json:"color"`
	// Heavy: plataformas de discos o cartuchos grandes (PS1 en adelante). La web y la app las destacan
	// en el uso de espacio.
	Heavy bool `json:"heavy"`
}

var knownSystems = map[string]System{
	"3do":             {Name: "3DO", Short: "3DO", Maker: "Panasonic", Color: "#c2410c", Heavy: true},
	"amiga":           {Name: "Amiga", Short: "Amiga", Maker: "Commodore", Color: "#64748b"},
	"arcade":          {Name: "Arcade", Short: "Arcade", Maker: "Varios", Color: "#ea580c"},
	"atari2600":       {Name: "Atari 2600", Short: "2600", Maker: "Atari", Color: "#b45309"},
	"atari7800":       {Name: "Atari 7800", Short: "7800", Maker: "Atari", Color: "#b45309"},
	"atarilynx":       {Name: "Atari Lynx", Short: "Lynx", Maker: "Atari", Color: "#b45309"},
	"dreamcast":       {Name: "Dreamcast", Short: "DC", Maker: "Sega", Color: "#f97316", Heavy: true},
	"fbneo":           {Name: "FinalBurn Neo", Short: "FBNeo", Maker: "Varios", Color: "#ea580c"},
	"gamegear":        {Name: "Game Gear", Short: "GG", Maker: "Sega", Color: "#1d4ed8"},
	"gb":              {Name: "Game Boy", Short: "GB", Maker: "Nintendo", Color: "#65a30d"},
	"gba":             {Name: "Game Boy Advance", Short: "GBA", Maker: "Nintendo", Color: "#6d28d9"},
	"gbc":             {Name: "Game Boy Color", Short: "GBC", Maker: "Nintendo", Color: "#16a34a"},
	"gc":              {Name: "GameCube", Short: "GC", Maker: "Nintendo", Color: "#7c3aed", Heavy: true},
	"genesis":         {Name: "Genesis", Short: "Genesis", Maker: "Sega", Color: "#1e293b"},
	"megadrive":       {Name: "Mega Drive", Short: "MD", Maker: "Sega", Color: "#1e293b"},
	"mame":            {Name: "MAME", Short: "MAME", Maker: "Varios", Color: "#ea580c"},
	"mastersystem":    {Name: "Master System", Short: "SMS", Maker: "Sega", Color: "#dc2626"},
	"n3ds":            {Name: "Nintendo 3DS", Short: "3DS", Maker: "Nintendo", Color: "#dc2626", Heavy: true},
	"3ds":             {Name: "Nintendo 3DS", Short: "3DS", Maker: "Nintendo", Color: "#dc2626", Heavy: true},
	"n64":             {Name: "Nintendo 64", Short: "N64", Maker: "Nintendo", Color: "#15803d"},
	"nds":             {Name: "Nintendo DS", Short: "DS", Maker: "Nintendo", Color: "#475569"},
	"neogeo":          {Name: "Neo Geo", Short: "Neo Geo", Maker: "SNK", Color: "#ca8a04"},
	"nes":             {Name: "NES", Short: "NES", Maker: "Nintendo", Color: "#b91c1c"},
	"famicom":         {Name: "Famicom", Short: "FC", Maker: "Nintendo", Color: "#b91c1c"},
	"ngp":             {Name: "Neo Geo Pocket", Short: "NGP", Maker: "SNK", Color: "#ca8a04"},
	"ngpc":            {Name: "Neo Geo Pocket Color", Short: "NGPC", Maker: "SNK", Color: "#ca8a04"},
	"pcengine":        {Name: "PC Engine", Short: "PCE", Maker: "NEC", Color: "#e11d48"},
	"pcenginecd":      {Name: "PC Engine CD", Short: "PCE CD", Maker: "NEC", Color: "#e11d48", Heavy: true},
	"psp":             {Name: "PlayStation Portable", Short: "PSP", Maker: "Sony", Color: "#334155", Heavy: true},
	"psvita":          {Name: "PlayStation Vita", Short: "Vita", Maker: "Sony", Color: "#2563eb", Heavy: true},
	"psx":             {Name: "PlayStation", Short: "PS1", Maker: "Sony", Color: "#6b7280", Heavy: true},
	"ps2":             {Name: "PlayStation 2", Short: "PS2", Maker: "Sony", Color: "#1e3a8a", Heavy: true},
	"ps3":             {Name: "PlayStation 3", Short: "PS3", Maker: "Sony", Color: "#111827", Heavy: true},
	"saturn":          {Name: "Saturn", Short: "Saturn", Maker: "Sega", Color: "#4b5563", Heavy: true},
	"sega32x":         {Name: "Sega 32X", Short: "32X", Maker: "Sega", Color: "#1e293b"},
	"segacd":          {Name: "Sega CD", Short: "Sega CD", Maker: "Sega", Color: "#1e293b", Heavy: true},
	"snes":            {Name: "Super Nintendo", Short: "SNES", Maker: "Nintendo", Color: "#7c3aed"},
	"sfc":             {Name: "Super Famicom", Short: "SFC", Maker: "Nintendo", Color: "#7c3aed"},
	"switch":          {Name: "Nintendo Switch", Short: "Switch", Maker: "Nintendo", Color: "#e60012", Heavy: true},
	"virtualboy":      {Name: "Virtual Boy", Short: "VB", Maker: "Nintendo", Color: "#b91c1c"},
	"wii":             {Name: "Wii", Short: "Wii", Maker: "Nintendo", Color: "#0ea5e9", Heavy: true},
	"wiiu":            {Name: "Wii U", Short: "Wii U", Maker: "Nintendo", Color: "#0284c7", Heavy: true},
	"wonderswan":      {Name: "WonderSwan", Short: "WS", Maker: "Bandai", Color: "#0f766e"},
	"wonderswancolor": {Name: "WonderSwan Color", Short: "WSC", Maker: "Bandai", Color: "#0f766e"},
	"xbox":            {Name: "Xbox", Short: "Xbox", Maker: "Microsoft", Color: "#16a34a", Heavy: true},
	"xbox360":         {Name: "Xbox 360", Short: "360", Maker: "Microsoft", Color: "#16a34a", Heavy: true},
	"ports":           {Name: "Ports", Short: "Ports", Maker: "Varios", Color: "#64748b"},
	"windows":         {Name: "Windows", Short: "PC", Maker: "Microsoft", Color: "#0369a1", Heavy: true},
}

func systemInfo(id string) System {
	if s, ok := knownSystems[strings.ToLower(id)]; ok {
		s.ID = id
		return s
	}
	return System{ID: id, Name: id, Short: strings.ToUpper(id), Maker: "", Color: "#64748b"}
}

// Extensiones que nunca son juegos: partidas, capturas, textos y archivos de sistema. Se ignoran en el escaneo
// (en la tablet, las partidas que WatermelonDS o RetroArch dejan junto al ROM no se cuentan ni se borran).
var ignoredExt = map[string]bool{
	".sav": true, ".srm": true, ".state": true, ".auto": true, ".mcr": true, ".mcd": true, ".dsv": true,
	".png": true, ".jpg": true, ".jpeg": true, ".webp": true, ".gif": true, ".bmp": true,
	".txt": true, ".nfo": true, ".xml": true, ".db": true, ".ini": true, ".cfg": true, ".json": true,
	".lnk": true, ".url": true, ".pdf": true, ".mp4": true, ".mkv": true, ".log": true, ".part": true,
	".rtc": true, ".eep": true, ".fla": true, ".mpk": true, ".sra": true, ".ldci": true, ".lst": false,
}

var ignoredNames = map[string]bool{
	"thumbs.db": true, "desktop.ini": true, ".ds_store": true, "systeminfo.txt": true, "gamelist.xml": true,
	".nomedia": true,
}

// Carpetas de medios o de sistema dentro de una plataforma: no se escanean.
var ignoredDirs = map[string]bool{
	"media": true, "images": true, "videos": true, "manuals": true, "downloaded_media": true,
	"screenshots": true, "covers": true, "snap": true, "boxart": true, "marquees": true, "saves": true,
	"states": true, ".romsync": true,
}

func isIgnoredFile(name string) bool {
	lower := strings.ToLower(name)
	if ignoredNames[lower] || strings.HasPrefix(lower, "._") {
		return true
	}
	ext := extOf(lower)
	if ignoredExt[ext] {
		return true
	}
	// Estados de RetroArch (.state1…), de melonDS (.ml1…) y de Snes9x/otros (.000…).
	if len(ext) >= 3 && (strings.HasPrefix(ext, ".state") || strings.HasPrefix(ext, ".ml") && allDigits(ext[3:]) && len(ext) > 3) {
		return true
	}
	return false
}

func allDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func extOf(name string) string {
	i := strings.LastIndexByte(name, '.')
	if i <= 0 || i == len(name)-1 {
		return ""
	}
	if strings.ContainsAny(name[i:], "/\\ ") {
		return ""
	}
	return strings.ToLower(name[i:])
}
