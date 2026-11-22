// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;

public class AudioPlayerManager : MonoBehaviour
{
    private static AudioPlayerManager instance;
    private AudioSource audio;

    // RVA: 0x2149
    private void Awake()
    {
        if (AudioPlayerManager.instance == null) {
            AudioPlayerManager.instance = this;
            Object.DontDestroyOnLoad(this.gameObject);
            return;
        }
        if (AudioPlayerManager.instance == this) {
            return;
        }
        Object.Destroy(this.gameObject);
    }

    // RVA: 0x2183
    private void Start()
    {
        this.audio = this.GetComponent<AudioSource>();
        this.audio.Play();
    }

}
