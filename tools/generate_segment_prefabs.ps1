# Generates the limb-segment prefabs the Stone Talus skeleton hangs off its arm and leg bones.
# The shipped Talus prefabs only cover the body, hand and foot; segments are the same rock palette
# in shapes sized so a bone's prefab fills the gap between its own pivot and its child's.
#
# Run from the repo root:  pwsh tools/generate_segment_prefabs.ps1

$ErrorActionPreference = 'Stop'
$outDir = Join-Path $PSScriptRoot '..\src\main\resources\Server\Prefabs\Titan\Talus'

function New-Prefab {
    param(
        [string]$Name,
        [int]$SizeX,
        [int]$SizeY,
        [int]$SizeZ,
        [string[]]$Skip
    )

    $skipSet = @{}
    foreach ($s in $Skip) { $skipSet[$s] = $true }

    $blocks = @()
    $fluids = @()
    for ($x = 0; $x -lt $SizeX; $x++) {
        for ($y = 0; $y -lt $SizeY; $y++) {
            for ($z = 0; $z -lt $SizeZ; $z++) {
                if ($skipSet.ContainsKey("$x,$y,$z")) { continue }

                # Deterministic palette so the same segment always looks the same, with cobble and moss
                # scattered through it the way the hand-built Talus prefabs are.
                $roll = ($x * 7 + $y * 13 + $z * 5) % 6
                $blockType = switch ($roll) {
                    0 { 'Rock_Stone_Cobble' }
                    4 { 'Rock_Stone_Mossy' }
                    default { 'Rock_Stone' }
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
    $json = $prefab | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "$Name : $($blocks.Count) blocks -> $path"
}

# Upper/lower arm: a 2x3x2 column with the two lower diagonal corners knocked off so the limb tapers.
New-Prefab -Name 'Talus_Arm_Segment' -SizeX 2 -SizeY 3 -SizeZ 2 -Skip @('0,0,0', '1,0,1')

# Upper/lower leg: a stubby 2x2x2 block. Legs are short and thick on a Talus.
New-Prefab -Name 'Talus_Leg_Segment' -SizeX 2 -SizeY 2 -SizeZ 2 -Skip @()
