# Generates the limb-segment prefabs the Stone Talus skeleton hangs off its arm and leg bones.
# The shipped Talus prefabs only cover the body, hand and foot; segments are the same rock palette
# in shapes sized so a bone's prefab fills the gap between its own pivot and its child's.
#
# Run from the repo root:  pwsh tools/generate_segment_prefabs.ps1

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $PSScriptRoot '..\src\main\resources\Server\Prefabs\Titan\Talus'

# One entry per rock type the Talus comes in, matching the palettes of the hand-built body, hand and foot
# prefabs. Base is the bulk of the limb, Cobble breaks it up, and Accent is what the hand-built prefabs use
# as their third block: moss on the default stone, and the variant's own cracked ore on everything else.
$palettes = [ordered]@{
    ''          = @{ Base = 'Rock_Stone';        Cobble = 'Rock_Stone_Cobble';        Accent = 'Rock_Stone_Mossy' }
    'Basalt'    = @{ Base = 'Rock_Basalt';       Cobble = 'Rock_Basalt_Cobble';       Accent = 'Ore_Iron_Basalt_Cracked' }
    'Slate'     = @{ Base = 'Rock_Slate';        Cobble = 'Rock_Slate_Cobble';        Accent = 'Ore_Cobalt_Slate_Cracked' }
    'Sandstone' = @{ Base = 'Rock_Sandstone';    Cobble = 'Rock_Sandstone_Cobble';    Accent = 'Ore_Thorium_Mud_Cracked' }
    'Magma'     = @{ Base = 'Rock_Magma_Cooled'; Cobble = 'Rock_Magma_Cooled_Cobble'; Accent = 'Ore_Adamantite_Magma_Cracked' }
}

function New-Prefab {
    param(
        [string]$Name,
        [int]$SizeX,
        [int]$SizeY,
        [int]$SizeZ,
        [string[]]$Skip,
        [hashtable]$Palette
    )

    $skipSet = @{}
    foreach ($s in $Skip) { $skipSet[$s] = $true }

    $blocks = @()
    $fluids = @()
    for ($x = 0; $x -lt $SizeX; $x++) {
        for ($y = 0; $y -lt $SizeY; $y++) {
            for ($z = 0; $z -lt $SizeZ; $z++) {
                if ($skipSet.ContainsKey("$x,$y,$z")) { continue }

                # Deterministic scatter so the same segment always looks the same, and so every rock type
                # gets its accent in the same places and the limbs stay recognisably the same shape.
                $roll = ($x * 7 + $y * 13 + $z * 5) % 6
                $blockType = switch ($roll) {
                    0 { $Palette.Cobble }
                    4 { $Palette.Accent }
                    default { $Palette.Base }
                }

                $blocks += [ordered]@{ x = $x; y = $y; z = $z; name = $blockType }
                $fluids += [ordered]@{ x = $x; y = $y; z = $z; name = 'Empty'; level = 0 }
            }
        }
    }

    $prefab = [ordered]@{
        version       = 8
        blockIdVersion = 11
        anchorX       = 0
        anchorY       = 0
        anchorZ       = 0
        blocks        = $blocks
        fluids        = $fluids
    }

    $path = Join-Path $outDir "$Name.prefab.json"
    # Must be BOM-less: the prefab loader parses the file as raw JSON and a BOM makes it throw at position 1.
    # Set-Content -Encoding UTF8 emits a BOM on Windows PowerShell, so write the bytes ourselves.
    $json = ($prefab | ConvertTo-Json -Depth 6) + "`n"
    [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "$Name : $($blocks.Count) blocks -> $path"
}

# Limb segments are deliberately long and only 2 blocks across. The bone scale that makes a segment span
# the gap to its child also sets its thickness, so a short stubby prefab can only be lengthened by making
# it fat. Four blocks of length against two of cross-section is what keeps the arms looking like arms next
# to a body that is 4 blocks tall.

foreach ($rock in $palettes.Keys) {
    # The default palette keeps the bare name; the rest take the same _<Rock> suffix the hand-built
    # body/hand/foot variants use, which is what the spawner appends for a variant's RockType.
    $suffix = if ($rock) { "_$rock" } else { '' }
    $palette = $palettes[$rock]

    # Upper/lower arm: diagonal corners knocked off each end so the limb tapers at both joints.
    New-Prefab -Name "Talus_Arm_Segment$suffix" -SizeX 2 -SizeY 4 -SizeZ 2 -Palette $palette `
        -Skip @('0,0,0', '1,0,1', '0,3,1', '1,3,0')

    # Upper/lower leg: a stubby 2x2x2 block. Legs are short and thick on a Talus, and their length is what
    # the gait was tuned against, so this one stays as it is.
    New-Prefab -Name "Talus_Leg_Segment$suffix" -SizeX 2 -SizeY 2 -SizeZ 2 -Palette $palette -Skip @()
}
