// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class SquareMoveUp : MonoBehaviour
{
    public GameObject Square;
    public float speed;
    private bool AllowSquareUp;
    public Button UpButton;

    // RVA: 0x27ea
    private void Start()
    {
    }

    // RVA: 0x27ec
    private void Update()
    {
        this.UpButton.onClick.AddListener(new UnityAction(this.UpClick));
        this.UpButton.onClick.AddListener(new UnityAction(this.UpClick));
        if (this.transform.position.y < 4.0f) {
            this.AllowSquareUp = true;
        }
        if (this.transform.position.y > 4.0f) {
            this.AllowSquareUp = false;
        }
    }

    // RVA: 0x2860
    private void UpClick()
    {
        bool local0 = true;
        this.AllowSquareUp = true;
        if (local0) {
            float local1 = Input.GetAxis("Horizontal");
            float local2 = Input.GetAxis("Vertical") + 1.0f;
            this.Square.transform.Translate(new Vector3(local1, local2, 0.0f) * this.speed * Time.deltaTime);
        }
    }

}
